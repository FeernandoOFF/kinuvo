package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.Route
import kinuvo.app.session.SessionViewModel
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.secret.SecretEntry
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionParams
import kinuvo.protocol.session.SessionStatus
import kinuvo.server.Secrets
import kinuvo.testing.harness.awaitCondition
import kinuvo.testing.harness.awaitValue
import kinuvo.testing.server.FakeSecretProvider
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.*

/** The `request_secret` card's state and answers, against a real Server. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecretRequestViewModelTest {
    private val pass = FakeSecretProvider(mapOf(listOf("Work", "GitHub", "token") to "ghp_private"))
    private val secrets = Secrets(listOf(pass, FakeSecretProvider(emptyMap(), scheme = "op", name = "1Password", connected = false)))

    @Test fun browsesNamesAndAnswersByPickingTypingOrDeclining() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try { withContext(main) { withTimeout(20000) {
                TestServer.start(secrets = secrets, script = {
                    on("deploy") { toolCall("request_secret", """{"label":"GitHub token","reason":"Publish the release"}"""); reply("Done") }
                }, scenario = { withoutDefaultAgent(); agent("work") }).use { server ->
                    val reader = object : DeviceReader {
                        override val kinds = emptyList<String>()
                        override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                        override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
                    }
                    val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
                    val stores = ViewModelStore()
                    try {
                        val client = server.client(token = server.auth.sessionToken())
                        owner.open(client, "local:test"); client.connect(); owner.activate()
                        val session = server.startSession(AgentId("work"))
                        val vm = SessionViewModel(client, owner.sessions, Route.Chat(AgentId("work"), session.id)); stores.put("chat", vm)
                        vm.state.first { !it.resolving }
                        vm.shown()

                        vm.loadSecretProviders()
                        assertEquals(listOf("pass" to 3), vm.state.first { it.secretProviders != null }.secretProviders!!.map { it.scheme to it.levels })
                        vm.browseSecrets(listOf("pass"))
                        vm.browseSecrets(listOf("pass", "Work"))
                        vm.browseSecrets(listOf("pass", "Work", "GitHub"))
                        assertEquals(listOf(SecretEntry("token")), vm.state.first { it.secretEntries.size == 3 }.secretEntries[listOf("pass", "Work", "GitHub")])

                        suspend fun ask(answer: (InteractionId) -> Unit): String {
                            val done = server.harness.store.entries(session.agentId, session.id).filterIsInstance<SessionEntry.ToolResult>().size
                            client.call(Api.Send, SendParams(session.agentId, session.id, "deploy"))
                            val card = vm.state.first { it.secretRequest != null }.secretRequest!!
                            assertEquals("GitHub token", card.label)
                            assertEquals("Publish the release", card.reason)
                            answer(card.id)
                            vm.state.first { it.secretRequest == null }
                            val output = awaitValue("the tool result") {
                                server.harness.store.entries(session.agentId, session.id).filterIsInstance<SessionEntry.ToolResult>().drop(done).firstOrNull()?.output
                            }
                            awaitCondition("the run to end") { client.call(Api.Session, SessionParams(session.agentId, session.id)).status == SessionStatus.Idle }
                            return output
                        }

                        assertEquals("\${pass://Work/GitHub/token}", ask { vm.pickSecret(it, listOf("pass", "Work", "GitHub", "token")) })
                        assertTrue(ask { vm.typeSecret(it, "typed-private") }.startsWith("\${secret://"))
                        assertFalse("typed-private" in vm.state.value.toString())
                        assertTrue("Don't ask for this credential in chat" in ask { vm.declineSecret(it) })
                        assertTrue(vm.state.value.errors.isEmpty(), vm.state.value.errors.toString())
                    } finally { stores.clear(); owner.close() }
                }
            } } } finally { Dispatchers.resetMain() }
        }
    }
}
