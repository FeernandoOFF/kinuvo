package kinuvo.harness.server.testing

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kinuvo.client.AuthException
import kinuvo.client.KinuvoClient
import kinuvo.protocol.AgentRef
import kinuvo.protocol.Api
import kinuvo.protocol.SendParams
import kinuvo.protocol.SessionChange
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionsParams
import kinuvo.protocol.TaskStatus
import kinuvo.protocol.TasksParams
import kinuvo.protocol.KnowledgeSearchParams
import kinuvo.protocol.CatchUpParams
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestServerTest {
    private fun KinuvoClient.entries(sessionId: String) =
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
                val sessions = client.call(Api.Sessions, SessionsParams("e2e")).sessions
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
                val session = client.call(Api.CreateSession, AgentRef("default"))
                client.entries(session.id).test {
                    client.call(Api.CatchUp, CatchUpParams("default", session.id))
                    client.call(Api.Send, SendParams("default", session.id, "Hello there"))
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
                val session = client.call(Api.CreateSession, AgentRef("default"))
                client.entries(session.id).test {
                    client.call(Api.CatchUp, CatchUpParams("default", session.id))
                    client.call(Api.Send, SendParams("default", session.id, "please remember tea"))
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
    fun servesRealWebSocketClientsToo() = runTest {
        val server = TestServer.start()
        server.use {
            withContext(Dispatchers.Default) {
                val url = server.listen()
                val client = KinuvoClient(url, "socket-phone", pairingCode = server.pairingCode)
                client.connect()
                assertEquals(listOf("default"), client.call(Api.Agents, Unit).map { it.id })
                assertEquals(0, server.spy.count(Api.Agents))
                client.close()
            }
        }
    }
}
