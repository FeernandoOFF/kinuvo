package kinuvo.harness.server

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.encodeToString
import kotlin.test.*
import kotlin.uuid.Uuid

class EmbeddedServerHostTest {
    private val home = Path(SystemTemporaryDirectory, "kinuvo-local-${Uuid.random()}")
    private val seen = mutableListOf<List<Message>>()
    private val offered = mutableListOf<List<String>>()
    private val provider = object : ModelProvider {
        override val id = "test"
        override suspend fun models() = listOf(ModelInfo("local"))
        override fun model(config: AgentConfig) = scriptedModel { prompt, tools ->
            val history = prompt.messages
            seen += history
            offered += tools.map { it.name }
            val user = history.filterIsInstance<Message.User>().last()
            when {
                user.textContent() == "remember tea" && history.last() is Message.User && history.toolResults.isEmpty() && tools.isNotEmpty() ->
                    flowOf(StreamFrame.ToolCallComplete("remember", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"add","target":"user","content":"Prefers tea"}"""), StreamFrame.End())
                history.toolResults.isNotEmpty() -> flowOf(StreamFrame.TextDelta("Saved."), StreamFrame.End())
                else -> flowOf(StreamFrame.TextDelta("Reply."), StreamFrame.End())
            }
        }
    }

    @AfterTest
    fun cleanup() { deleteTree(home) }

    @Test
    fun failedFreshInstallationClosesPluginsOnce() = runBlocking {
        var closes = 0
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.localservertest.1", "Test")
            override suspend fun close() { closes++ }
        }
        val failing = object : ModelProvider by provider {
            override suspend fun models(): List<ModelInfo> = throw CancellationException("Discovery cancelled")
        }
        assertFailsWith<CancellationException> { EmbeddedServerHost.create(home, listOf(failing), listOf(plugin)) }
        assertEquals(1, closes)
    }

    @Test
    fun freshInstallationKeepsTheDefaultModelBeforeSignIn() = runBlocking {
        val signedOut = object : ModelProvider by provider {
            override suspend fun models(): List<ModelInfo> = error("No login")
        }
        val server = EmbeddedServerHost.create(home, listOf(signedOut))
        try {
            val config = server.runtime.harness.store.agents().single().config
            assertEquals(provider.id, config.provider)
            assertEquals(AgentConfig().model, config.model)
        } finally { server.close() }
    }

    @Test
    fun cancelledFreshInstallationFinishesPluginCleanup() = runBlocking {
        withTimeout(10_000) {
            var closes = 0
            val discovering = CompletableDeferred<Unit>()
            val plugin = object : HarnessPlugin {
                override val definition = kinuvo.protocol.PluginDefinition("test.localservertest.2", "Test")
                override suspend fun close() { yield(); closes++ }
            }
            val waiting = object : ModelProvider by provider {
                override suspend fun models(): List<ModelInfo> { discovering.complete(Unit); awaitCancellation() }
            }
            val creation = launch { EmbeddedServerHost.create(home, listOf(waiting), listOf(plugin)) }
            discovering.await()
            creation.cancelAndJoin()
            assertEquals(1, closes)
        }
    }

    @Test
    fun thePhoneRunningTheServerIsItsOwnComputer(): Unit = runBlocking {
        withTimeout(10_000) {
            val server = EmbeddedServerHost.create(home, listOf(provider), continuous = true)
            val client = KinuvoClient("local://kinuvo", "Pixel", token = server.token, connection = server::connect,
                deviceTools = { setOf(DeviceToolKind.Computer) }, computer = { ComputerResult(DeviceToolStatus.Ready, screen = ComputerScreen("a1", 10, 20, app = "com.example")) })
            try {
                client.connect()
                client.setActive(false)
                val computers = server.runtime.harness.computers
                assertEquals(listOf("Pixel" to true), computers.list().map { it.name to it.available })
                assertEquals("com.example", computers.all().single().perform(ComputerRequest()).screen?.app)
            } finally { client.close(); server.close() }
        }
    }

    @Test
    fun memoryAndSessionsSurviveServerRecreation(): Unit = runBlocking {
        withTimeout(10_000) {
            var server = EmbeddedServerHost.create(home, listOf(provider))
            var client = client(server)
            try {
                assertEquals("test", client.call(Api.Agent, AgentRef("default")).settings.provider)
                val id = client.call(Api.CreateSession, AgentRef("default")).id
                val updates = run(client, id, "remember tea")
                val entries = updates.filterIsInstance<SessionChange.Entry>().map { it.entry }
                assertTrue(entries.any { it is SessionEntry.ToolResult && !it.isError })
                assertEquals("Saved.", entries.filterIsInstance<SessionEntry.AssistantMessage>().last().text)
                assertEquals("- Prefers tea\n", FileAgentDocs(AgentFiles(home)).read("default", AgentDoc.UserNotes))
                client.close()
                server.close()
                server = EmbeddedServerHost.create(home, listOf(provider))
                client = client(server)
                assertTrue(client.call(Api.Sessions, SessionsParams("default")).sessions.any { it.id == id })
                val second = client.call(Api.CreateSession, AgentRef("default")).id
                run(client, second, "what do I prefer?")
                assertTrue(seen.last().filterIsInstance<Message.System>().last().textContent().contains("Prefers tea"))
                assertFalse(seen.last().filterIsInstance<Message.User>().any { it.textContent() == "remember tea" })
                assertFailsWith<IllegalStateException> { client.call(Api.Send, SendParams("default", "missing", "hello")) }
                assertFailsWith<IllegalStateException> { client.call(Api.ConfigureSession, ConfigureSessionParams("default", second, SessionSettings(provider = "cloud", model = "x"))) }
            } finally { client.close(); server.close() }
        }
    }

    @Test
    fun pendingApprovalSurvivesReconnectAndAnApprovedRunFinishesInTheBackground(): Unit = runBlocking {
        withTimeout(10_000) {
            val server = EmbeddedServerHost.create(home, listOf(provider))
            var client = client(server)
            try {
                val id = client.call(Api.CreateSession, AgentRef("default")).id
                client.call(Api.ConfigureSession, ConfigureSessionParams("default", id, SessionSettings(approvals = "strict")))
                client.call(Api.CatchUp, CatchUpParams("default", id))
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    client.events(Api.Update).mapNotNull { (it.change as? SessionChange.Event)?.event as? AgentEvent.ApprovalRequested }.first()
                }
                client.call(Api.Send, SendParams("default", id, "remember tea"))
                val approval = request.await()
                assertEquals("", FileAgentDocs(AgentFiles(home)).read("default", AgentDoc.UserNotes))
                assertFailsWith<IllegalStateException> { client.call(Api.Send, SendParams("default", id, "again")) }
                client.close()
                client = client(server)
                assertEquals(SessionStatus.Waiting, client.call(Api.Session, SessionParams("default", id)).status)
                assertTrue(client.call(Api.Approve, ApprovalParams(approval.approvalId, ApprovalDecision.AllowOnce)))
                // An approved run doesn't wait on anyone, so leaving the app lets it finish.
                client.call(Api.Presence, PresenceParams(false))
                while (client.call(Api.Session, SessionParams("default", id)).status != SessionStatus.Idle) kotlinx.coroutines.delay(20)
                server.runtime.runActivity.value.let { assertEquals(0, it.runs); assertTrue(it.steps > 0) }
                assertFalse(client.call(Api.Approve, ApprovalParams(approval.approvalId, ApprovalDecision.AllowOnce)))
            } finally { client.close(); server.close() }
        }
    }

    @Test
    fun backgroundCancellationDoesNotSaveUnapprovedMemory(): Unit = runBlocking {
        withTimeout(10_000) {
            val server = EmbeddedServerHost.create(home, listOf(provider))
            val client = client(server)
            try {
                val id = client.call(Api.CreateSession, AgentRef("default")).id
                client.call(Api.ConfigureSession, ConfigureSessionParams("default", id, SessionSettings(approvals = "strict")))
                client.call(Api.CatchUp, CatchUpParams("default", id))
                val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                    client.events(Api.Update).mapNotNull { (it.change as? SessionChange.Event)?.event as? AgentEvent.ApprovalRequested }.first()
                }
                client.call(Api.Send, SendParams("default", id, "remember tea"))
                waiting.await()
                client.call(Api.Presence, PresenceParams(false))
                assertEquals(SessionStatus.Idle, client.call(Api.Session, SessionParams("default", id)).status)
                assertEquals("", FileAgentDocs(AgentFiles(home)).read("default", AgentDoc.UserNotes))
            } finally { client.close(); server.close() }
        }
    }

    @Test
    fun offersNoToolsForFeaturesTheServerLacks(): Unit = runBlocking {
        withTimeout(10_000) {
            val server = EmbeddedServerHost.create(home, listOf(provider))
            val client = client(server)
            try {
                run(client, client.call(Api.CreateSession, AgentRef("default")).id, "hi")
                assertTrue(kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), ToolNames.MEMORY).modelAlias() in offered.last())
                for (local in listOf(ToolNames.ACTIVATE_SKILL, "read_skill_file", ToolNames.SCHEDULE, ToolNames.ARTIFACT))
                    assertFalse(kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), local).modelAlias() in offered.last())
                assertTrue(kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.tasks"), ToolNames.TASKS).modelAlias() in offered.last())
            } finally { client.close(); server.close() }
        }
    }

    @Test
    fun advertisesDeviceToolsAndTextOnlyInput(): Unit = runBlocking {
        withTimeout(10_000) {
            val server = EmbeddedServerHost.create(home, listOf(provider))
            val client = client(server)
            try {
                assertEquals(setOf(ServerFeature.DeviceTools, ServerFeature.Computer, ServerFeature.Knowledge, ServerFeature.Tasks, ServerFeature.Screens), client.lastConnect?.capabilities?.features)
                val offered = client.lastConnect?.capabilities
                assertFalse(offered.offers(Integration.Exec) || offered.offers(Integration.Secrets) || offered.offers(Integration.Mcp))
                assertTrue(offered.offers(Integration.Tasks, "local") && offered.offers(Integration.Knowledge, "markdown"))
                assertTrue(offered.offers(Integration.Tools, "memory") && !offered.offers(Integration.Tools, "run_code"))
                val capabilities = client.call(Api.Capabilities, CapabilitiesParams("default"))
                assertTrue(capabilities.chat.actionable)
                assertFalse(capabilities.input("image").actionable)
                assertFalse(capabilities.input("file").actionable)
            } finally { client.close(); server.close() }
        }
    }

    @Test
    fun providerSignInHandsOutACodeAndReportsTheResult(): Unit = runBlocking {
        withTimeout(10_000) {
            val approve = CompletableDeferred<Unit>()
            var signedIn = false
            val auth = object : ProviderAuth {
                override suspend fun account() = ProviderAccount(signedIn, "me@example.com".takeIf { signedIn })
                override suspend fun login(show: (DeviceCode) -> Unit) { show(DeviceCode("https://sign.in", "ABCD", 0)); approve.await(); signedIn = true }
                override suspend fun logout() { signedIn = false }
            }
            val server = EmbeddedServerHost.create(home, listOf(object : ModelProvider by provider { override val auth = auth }))
            val client = client(server)
            try {
                assertEquals(ProviderAccount(false), client.call(Api.Providers, Unit).single().account)
                val changed = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.ProvidersChanged).first() }
                assertEquals("ABCD", client.call(Api.LoginProvider, ProviderRef("test")).code)
                approve.complete(Unit)
                assertEquals(ProviderAccount(true, "me@example.com"), changed.await().single().account)
                client.call(Api.LogoutProvider, ProviderRef("test"))
                assertFalse(client.call(Api.Providers, Unit).single().account!!.signedIn)
            } finally { client.close(); server.close() }
        }
    }

    @Test
    fun clientConfiguresApiKeyAndReceivesOnlyStatus(): Unit = runBlocking {
        var value = ""
        val key = object : ProviderApiKey {
            override suspend fun status() = ProviderApiKeyStatus(value.isNotEmpty(), "Test storage", value.isNotEmpty())
            override suspend fun save(value: String) { keyValue(value) }
            private fun keyValue(next: String) { value = next }
        }
        val server = EmbeddedServerHost.create(home, listOf(object : ModelProvider by provider { override val apiKey = key }))
        val client = client(server)
        try {
            assertFalse(client.call(Api.Providers, Unit).single().apiKey!!.configured)
            val changed = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.ProvidersChanged).first() }
            client.call(Api.SetProviderApiKey, SetProviderApiKeyParams("test", "test-key"))
            assertEquals("test-key", value)
            val info = changed.await().single()
            assertTrue(info.apiKey!!.configured)
            assertFalse(ProtocolJson.encodeToString(info).contains("test-key"))
            client.call(Api.SetProviderApiKey, SetProviderApiKeyParams("test", ""))
            assertFalse(client.call(Api.Providers, Unit).single().apiKey!!.configured)
        } finally { client.close(); server.close(); deleteTree(home) }
    }

    private suspend fun client(server: EmbeddedServerHost) = KinuvoClient("local://kinuvo", "test", token = server.token, connection = server::connect).also { it.connect() }

    private suspend fun CoroutineScope.run(client: KinuvoClient, id: String, text: String): List<SessionChange> {
        client.call(Api.CatchUp, CatchUpParams("default", id))
        val updates = async(start = CoroutineStart.UNDISPATCHED) {
            client.events(Api.Update).filter { it.sessionId == id }.map { it.change }
                .transformWhile { emit(it); (it as? SessionChange.Event)?.event !is AgentEvent.RunFinished }.toList()
        }
        client.call(Api.Send, SendParams("default", id, text))
        return updates.await()
    }
}
