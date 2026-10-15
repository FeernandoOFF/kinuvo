package kinuvo.harness.server

import kinuvo.harness.core.modelAlias
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.Tool
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class RuntimeDependenciesTest {
    @Test
    fun runsUseInjectedDocumentsStoreCatalogAndWebFetch() = runTest {
        val fixture = RuntimeFixture()
        fixture.documents[AgentDoc.Soul] = "Injected soul"
        var fetched = false
        fixture.fetch = { object : Tool {
            override val descriptor = ToolDescriptor("web_fetch", "Injected fetch")
            override suspend fun execute(args: JsonObject): String { fetched = true; return "Injected page" }
        } }
        val model = Model { instructions, history, tools ->
            assertTrue("Injected soul" in instructions)
            assertEquals(setOf("memory", "web_fetch").map { kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), it).modelAlias() }.toSet(), tools.map { it.name }.toSet())
            when (history.filterIsInstance<SessionEntry.ToolResult>().size) {
                0 -> flowOf(StreamFrame.ToolCallComplete("memory", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"add","target":"memory","content":"Injected memory"}"""), StreamFrame.End())
                1 -> flowOf(StreamFrame.ToolCallComplete("fetch", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "web_fetch").modelAlias(), "{}"), StreamFrame.End())
                else -> flowOf(StreamFrame.TextDelta("Done"), StreamFrame.End())
            }
        }
        val runtime = fixture.runtime(model)
        val agent = fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf("memory", "web_fetch"), approvals = "full"))
        try {
            val session = runtime.newSession(agent)
            runtime.runner(agent, session.id).run(session.id, "Go").toList()
            assertEquals("- Injected memory", fixture.documents[AgentDoc.Memory]?.trim())
            assertTrue(fetched)
            assertTrue(fixture.catalogReads > 0)
            assertEquals("Done", fixture.transcripts.entries(agent, session.id).filterIsInstance<SessionEntry.AssistantMessage>().single().text)
        } finally { runtime.close() }
    }

    @Test
    fun reflectionUsesInjectedProgressAndDoesNotRepeatCompletedWork() = runTest {
        val fixture = RuntimeFixture()
        var requests = 0
        val runtime = fixture.runtime(Model { _, _, _ -> requests++; flowOf(StreamFrame.End()) })
        val agent = fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf("memory")))
        val session = runtime.newSession(agent)
        runtime.store.append(agent, session.id, SessionEntry.UserMessage("Remember"))
        val reflector = Reflector(runtime, backgroundScope, { false })
        try {
            reflector.reflect(agent, session.id)
            assertTrue(session.id in fixture.progress)
            val after = requests
            reflector.reflect(agent, session.id)
            assertEquals(after, requests)
        } finally { runtime.close() }
    }

    @Test
    fun resourceOwnerReleasesEveryPluginAndClosesOnceEvenWhenCancelled() = runTest {
        val actions = mutableListOf<String>()
        val first = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.runtimedependenciestest.1", "Test")
            override suspend fun release(agentId: String) { actions += "release first"; error("Release failed") }
            override suspend fun close() { actions += "close first"; yield(); error("Close failed") }
        }
        val second = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.runtimedependenciestest.2", "Test")
            override suspend fun release(agentId: String) { actions += "release second" }
            override suspend fun close() { actions += "close second"; yield() }
        }
        val owner = ServerResources(listOf(first, second, first)) { actions += "close network" }
        assertFailsWith<IllegalStateException> { owner.release("test") }
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { owner.close() }
        closing.cancelAndJoin()
        owner.close()
        owner.release("test")
        assertEquals(listOf("release first", "release second", "close first", "close second", "close network"), actions)
    }

    @Test
    fun activeProtocolRequestsFinishBeforeResourcesClose() = runTest {
        val entered = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val provider = object : ModelProvider {
            override val id = "test"
            override suspend fun models(): List<ModelInfo> {
                entered.complete(Unit)
                try { awaitCancellation() } finally { actions += "request stopped" }
            }
            override fun model(config: AgentConfig): Model = error("Not used")
        }
        val fixture = RuntimeFixture(providers = listOf(provider), closeNetwork = { actions += "resources closed" })
        val peers = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "test"
        }
        val requests = kotlinx.coroutines.channels.Channel<Frame>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val responses = kotlinx.coroutines.channels.Channel<Frame>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val connection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) { responses.send(frame) }
            override suspend fun close() { requests.close(); responses.close() }
        }
        val server = ServerRuntime(fixture.runtime(), peers)
        server.start()
        val serving = launch { server.serve(connection) }
        try {
            requests.send(Frame.Request("connect", Api.Connect.name, ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("test"))))
            assertTrue(assertIs<Frame.Response>(responses.receive()).ok)
            requests.send(Frame.Request("models", Api.Models.name, ProtocolJson.encodeToJsonElement(ModelsParams.serializer(), ModelsParams())))
            entered.await()
            server.close()
            serving.join()
            assertEquals(listOf("request stopped", "resources closed"), actions)
        } finally { server.close(); serving.cancelAndJoin() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    @Test
    fun serverWorkFinishesBeforeOwnedResourcesClose() = runTest {
        val collecting = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val state = kotlinx.coroutines.flow.MutableStateFlow<Usage?>(null)
        val provider = object : ModelProvider {
            override val id = "test"
            override val usage = object : kotlinx.coroutines.flow.StateFlow<Usage?> by state {
                override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Usage?>): Nothing {
                    collecting.complete(Unit)
                    try { state.collect(collector) } finally { actions += "work stopped" }
                }
            }
            override fun model(config: AgentConfig): Model = error("Not used")
        }
        val fixture = RuntimeFixture(providers = listOf(provider), closeNetwork = { actions += "resources closed" })
        val peers = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?): String? = null
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "test"
        }
        val server = ServerRuntime(fixture.runtime(), peers)
        server.start()
        collecting.await()
        server.close()
        server.close()
        assertEquals(listOf("work stopped", "resources closed"), actions)
    }

    @Test
    fun failedStartClosesResourcesAndCannotRestart() = runTest {
        var closes = 0
        val management = object : McpManagement by McpManagement.Unavailable {
            override fun onSignInNeeded(listener: suspend (agentId: String, server: String) -> Unit) { error("Start failed") }
        }
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.runtimedependenciestest.3", "Test")
            override val mcp = management
            override suspend fun close() { closes++ }
        }
        val fixture = RuntimeFixture(listOf(plugin), closeNetwork = { closes++ })
        val server = ServerRuntime(fixture.runtime(), object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?): String? = null
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "test"
        })
        assertFailsWith<IllegalStateException> { server.start() }
        server.close()
        assertEquals(2, closes)
        assertFailsWith<IllegalStateException> { server.start() }
    }
}
