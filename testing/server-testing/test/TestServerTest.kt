package kinuvo.testing.server

import kotlin.time.Duration.Companion.seconds
import kinuvo.testing.harness.awaitValue
import kinuvo.testing.harness.awaitCondition
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kinuvo.client.AuthException
import kinuvo.client.KinuvoClient
import kinuvo.protocol.agent.AgentRef
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.Api
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.RespondParams
import kinuvo.protocol.approval.approval
import kinuvo.protocol.browser.BrowserOwner
import kinuvo.protocol.browser.BrowserTab
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionsParams
import kinuvo.protocol.board.TaskStatus
import kinuvo.protocol.board.TasksParams
import kinuvo.protocol.knowledge.KnowledgeSearchParams
import kinuvo.protocol.session.CatchUpParams
import kinuvo.protocol.session.HistoryParams
import kinuvo.protocol.model.ModelsParams
import kinuvo.protocol.session.SessionParams
import kinuvo.protocol.execution.ExecutionCapabilities
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.execution.ExecutionRef
import kinuvo.protocol.model.ModelInfo
import kinuvo.protocol.approval.Question
import kinuvo.protocol.approval.QuestionKind
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.agent.AgentId
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestServerTest {
    private fun KinuvoClient.entries(sessionId: SessionId) =
        events(Api.Update).filter { it.sessionId == sessionId }.mapNotNull { (it.change as? SessionChange.Entry)?.entry }

    private suspend inline fun <reified E : SessionEntry> ReceiveTurbine<SessionEntry>.awaitEntry(): E {
        while (true) (awaitItem() as? E)?.let { return it }
    }

    @Test
    fun pairsAndServesTheSeededAgentsAndSessions() = runTest {
        val server = TestServer.start(scenario = {
            val agent = agent("e2e", "E2E Agent")
            withoutDefaultAgent()
            session(agent, title = "Planning") { user("Plan the week"); assistant("Here is a plan") }
        })
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client()
                val connected = client.connect()
                assertEquals("Test Server", connected.serverName)
                assertEquals(listOf("E2E Agent"), client.call(Api.Agents, Unit).map { it.name })
                val sessions = client.call(Api.Sessions, SessionsParams(AgentId("e2e"))).sessions
                assertEquals(listOf("Planning"), sessions.map { it.title })
                assertEquals(1, server.spy.count(Api.Sessions))
                assertEquals(1, server.spy.count(Api.Connect))
                assertEquals(0, server.spy.count(Api.CreateSession))
            }
        }
    }

    @Test
    fun streamsTheScriptedReplyToAMessage() = runTest {
        val server = TestServer.start(script = { on("Hello") { reply("Hello from the script") } })
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val session = client.call(Api.CreateSession, AgentRef(AgentId("default")))
                client.entries(session.id).test {
                    client.call(Api.CatchUp, CatchUpParams(AgentId("default"), session.id))
                    client.call(Api.Send, SendParams(AgentId("default"), session.id, "Hello there"))
                    assertEquals("Hello there", awaitEntry<SessionEntry.UserMessage>().text)
                    assertEquals("Hello from the script", awaitEntry<SessionEntry.AssistantMessage>().text)
                    cancelAndIgnoreRemainingEvents()
                }
                assertEquals(1, server.spy.count(Api.Send))
                assertEquals(listOf(Api.Connect.name), server.spy.methods.take(1))
            }
        }
    }

    @Test
    fun playsAToolCallBeforeTheAnswer() = runTest {
        val server = TestServer.start(script = {
            on("remember") {
                toolCall("memory", """{"target":"memory","action":"add","content":"Likes tea"}""")
                reply("Remembered")
            }
        })
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val session = client.call(Api.CreateSession, AgentRef(AgentId("default")))
                client.entries(session.id).test {
                    client.call(Api.CatchUp, CatchUpParams(AgentId("default"), session.id))
                    client.call(Api.Send, SendParams(AgentId("default"), session.id, "please remember tea"))
                    assertEquals("memory", awaitEntry<SessionEntry.ToolCall>().name)
                    assertEquals("memory", awaitEntry<SessionEntry.ToolResult>().name)
                    assertEquals("Remembered", awaitEntry<SessionEntry.AssistantMessage>().text)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    }

    @Test
    fun servesSeededBoardAndKnowledge() = runTest {
        val server = TestServer.start(scenario = {
            task("Plan mobile tests")
            task("Completed fixture task", status = TaskStatus.Done)
            note("project", "Kinuvo project", "Testing mobile workflows")
        })
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                assertEquals(listOf("Plan mobile tests"), client.call(Api.Tasks, TasksParams()).map { it.title })
                assertEquals(setOf("Plan mobile tests", "Completed fixture task"), client.call(Api.Tasks, TasksParams(includeDone = true)).map { it.title }.toSet())
                assertEquals(listOf("Kinuvo project"), client.call(Api.KnowledgeSearch, KnowledgeSearchParams("Kinuvo")).map { it.title })
                assertEquals(2, server.spy.count(Api.Tasks))
            }
        }
    }

    @Test
    fun anAlreadyPairedTokenAuthenticatesAndABadCodeIsRefused() = runTest {
        val server = TestServer.start()
        server.use {
            withContext(Dispatchers.Default) {
                val token = server.pairDevice("phone")
                val client = server.client("phone", token = token)
                client.connect()
                assertEquals(listOf("phone"), server.auth.devices().map { it.name })
                client.close()
                assertFailsWith<AuthException> {
                    KinuvoClient("memory://test-server", "bad", pairingCode = "000000", connection = { server.connect() }).connect()
                }
            }
        }
    }

    @Test
    fun listsFakeBrowserTabsAndPairedComputersAsScreens() = runTest {
        val tab = BrowserTab("tab", AgentId("default"), "Private site", "http://lab.internal")
        val server = TestServer.start(plugins = listOf(FakeBrowserPlugin(FakeBrowser(listOf(tab)))))
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                server.computer("Pixel")
                val screens = client.call(Api.Screens, null)
                assertEquals(setOf("Pixel", "Private site"), screens.map { it.title }.toSet())
                assertEquals(listOf(AgentId("default")), client.call(Api.Screens, BrowserOwner(AgentId("default"))).mapNotNull { it.ref.owner?.agentId })
            }
        }
    }

    @Test
    fun aFakeToolAboveTheToleranceWaitsForAnApproval() = runTest {
        val server = TestServer.start(
            plugins = listOf(FakeTools(FakeTool("shell", Risk.Medium, reason = "Runs a test command") { "test" })),
            script = { on("run") { toolCall("shell"); reply("Done") } },
        )
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val session = client.call(Api.CreateSession, AgentRef(AgentId("default")))
                val approvals = client.events(Api.Update).mapNotNull { ((it.change as? SessionChange.Event)?.event?.approval) }
                approvals.test {
                    client.call(Api.CatchUp, CatchUpParams(AgentId("default"), session.id))
                    client.call(Api.Send, SendParams(AgentId("default"), session.id, "please run it"))
                    val request = awaitItem()
                    assertEquals("Runs a test command", request.reason)
                    client.entries(session.id).test {
                        assertTrue(client.call(Api.Respond, RespondParams(session.id, request.id, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
                        assertEquals("test", awaitEntry<SessionEntry.ToolResult>().output)
                        assertEquals("Done", awaitEntry<SessionEntry.AssistantMessage>().text)
                        cancelAndIgnoreRemainingEvents()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    }

    @Test
    fun servesRealWebSocketClientsToo() = runTest {
        val server = TestServer.start()
        server.use {
            withContext(Dispatchers.Default) {
                val url = server.listen()
                val client = KinuvoClient(url, "socket-phone", pairingCode = server.pairingCode)
                client.connect()
                assertEquals(listOf("default"), client.call(Api.Agents, Unit).map { it.id.value })
                assertEquals(0, server.spy.count(Api.Agents))
                client.close()
            }
        }
    }

    @Test
    fun anExternalHarnessAsksAQuestionThatAClientAnswers() = runTest {
        val harness = FakeHarness("fake") { prompt ->
            say("Working on $prompt")
            askQuestions("Pick a limit", Question("limit", "Limit", QuestionKind.Number))
            reply("Limit accepted")
        }
        val server = TestServer.start(harnesses = listOf(harness), scenario = { externalAgent(harness, "ext", "External") })
        server.use {
            withContext(Dispatchers.Default) {
                val session = server.startSession(AgentId("ext"), "Start", title = "External run")
                val client = server.client().also { it.connect() }
                assertEquals(true, client.call(Api.ProbeHarnessConnection, harness.connection).readiness.online)
                val pending = awaitValue("a pending request", 5.seconds) { client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value)).pending.takeIf { it.isNotEmpty() } }.single()
                assertIs<PendingInteraction.Questions>(pending)
                assertEquals(ExecutionPhase.RequiresAction, client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value)).phase)
                assertTrue(client.call(Api.Respond, RespondParams(session.id, pending.id, InteractionAnswer.Questions(emptyMap()))))
                awaitCondition("the run to go idle", 5.seconds) { client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value)).phase == ExecutionPhase.Idle }
                val entries = client.call(Api.History, HistoryParams(AgentId("ext"), session.id, 100)).map { it.entry }
                assertEquals(listOf("Working on Start", "Limit accepted"), entries.filterIsInstance<SessionEntry.AssistantMessage>().map { it.text })
                assertEquals("External run", client.call(Api.Session, SessionParams(AgentId("ext"), session.id)).title)
                assertEquals(1, server.spy.count(Api.Respond))
                assertEquals(0, server.spy.count(Api.Send))
            }
        }
    }

    @Test
    fun aLostExternalSessionKeepsItsTranscriptAndAnInterruptedDraft() = runTest {
        val harness = FakeHarness("fake", capabilities = ExecutionCapabilities(supportedMimeTypes = setOf("text/plain")))
        val server = TestServer.start(harnesses = listOf(harness), scenario = {
            externalAgent(harness, "ext", "External")
            lostExternalSession(AgentId("ext"), title = "Lost run", draft = "Half an answer", support = harness.capabilities) { user("Start"); assistant("Saved reply") }
        })
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val session = client.call(Api.Sessions, SessionsParams(AgentId("ext"))).sessions.single()
                assertEquals("Lost run", session.title)
                assertEquals(ExecutionPhase.Lost, client.call(Api.ExecutionSnapshot, ExecutionRef(session.id.value)).phase)
                val notice = client.call(Api.History, HistoryParams(AgentId("ext"), session.id, 100)).map { it.entry }.filterIsInstance<SessionEntry.Notice>().single()
                assertEquals("Half an answer", (notice.draft.single() as SessionEntry.AssistantMessage).text)
            }
        }
    }

    @Test
    fun listsFakeModelProvidersWithTheirModels() = runTest {
        val models = FakeModels(FakeModelProvider("fake", "Fake models", listOf(ModelInfo("m1")), signedIn = true), FakeModelProvider("keyed", "Keyed", needsKey = true))
        val server = TestServer.start(plugins = listOf(models))
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val providers = client.call(Api.Providers, Unit)
                assertEquals(setOf("fake", "keyed"), providers.map { it.id }.toSet())
                assertEquals(true, providers.single { it.id == "fake" }.account?.signedIn)
                assertEquals(false, providers.single { it.id == "keyed" }.apiKey?.configured)
                assertEquals(listOf("m1"), client.call(Api.Models, ModelsParams()).providers.single { it.id == "fake" }.models.map { it.id })
            }
        }
    }
}
