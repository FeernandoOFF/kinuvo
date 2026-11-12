package kinuvo.testing.server

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kinuvo.client.KinuvoClient
import kinuvo.harness.AgentDoc
import kinuvo.harness.RunKind
import kinuvo.testing.harness.awaitCondition
import kinuvo.protocol.AgentRef
import kinuvo.protocol.Api
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.CatchUpParams
import kinuvo.protocol.HistoryParams
import kinuvo.protocol.InteractionAnswer
import kinuvo.protocol.RespondParams
import kinuvo.protocol.Risk
import kinuvo.protocol.RoutineParams
import kinuvo.protocol.RunStatus
import kinuvo.protocol.SendParams
import kinuvo.protocol.SessionChange
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionParams
import kinuvo.protocol.SessionStatus
import kinuvo.protocol.approval
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The built-in harness is one more [kinuvo.harness.ExecutionFactory]: every run of an Agent starts from a prompt with a run kind. */
class NativeExecutionPathTest {
    private val deploy = FakeTool("deploy", Risk.High, reason = "Pushes to production") { "deployed" }

    private fun KinuvoClient.entries(sessionId: String) =
        events(Api.Update).filter { it.sessionId == sessionId }.mapNotNull { (it.change as? SessionChange.Entry)?.entry }

    private suspend inline fun <reified E : SessionEntry> ReceiveTurbine<SessionEntry>.awaitEntry(): E {
        while (true) (awaitItem() as? E)?.let { return it }
    }

    @Test
    fun aChatTurnRunsItsToolAfterAnApprovalAndCommitsTheTranscript() = runBlocking {
        lateinit var session: SessionInfo
        val server = TestServer.start(
            plugins = listOf(FakeTools(deploy)),
            script = { on("please deploy") { toolCall("deploy"); reply("Done") } },
            scenario = { session = session(agent("work")) },
        )
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val approvals = client.events(Api.Update).mapNotNull { (it.change as? SessionChange.Event)?.event?.approval }
                approvals.test {
                    client.call(Api.CatchUp, CatchUpParams("work", session.id))
                    client.call(Api.Send, SendParams("work", session.id, "please deploy"))
                    val approval = awaitItem()
                    assertEquals("deploy", approval.toolCallName)
                    assertEquals(SessionStatus.Waiting, client.call(Api.Session, SessionParams("work", session.id)).status)
                    client.entries(session.id).test {
                        assertTrue(client.call(Api.Respond, RespondParams(session.id, approval.id, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
                        assertEquals("deployed", awaitEntry<SessionEntry.ToolResult>().output)
                        assertEquals("Done", awaitEntry<SessionEntry.AssistantMessage>().text)
                        cancelAndIgnoreRemainingEvents()
                    }
                    cancelAndIgnoreRemainingEvents()
                }
                awaitCondition("the run to end", 5.seconds) { client.call(Api.Session, SessionParams("work", session.id)).status == SessionStatus.Idle }
                val transcript = client.call(Api.History, HistoryParams("work", session.id, 100)).map { it.entry }
                    .filter { it is SessionEntry.UserMessage || it is SessionEntry.ToolCall || it is SessionEntry.ToolResult || it is SessionEntry.AssistantMessage }
                assertEquals(listOf("UserMessage", "ToolCall", "ToolResult", "AssistantMessage"), transcript.map { it::class.simpleName })
                val sent = server.prompts.inputs.single()
                assertIs<RunKind.Chat>(sent.kind)
                assertEquals("please deploy", sent.text)
            }
        }
    }

    @Test
    fun aRoutineRunStartsAsARoutineKindAndReportsItsOutcome() = runBlocking {
        val server = TestServer.start(
            script = { on("Summarize the news") { toolCall("routine_report", """{"outcome":"report","summary":"Two things are new"}"""); reply("Reported") } },
            schedule = true,
            scenario = { routine(agent("work"), "Brief", "Summarize the news") },
        )
        server.use {
            withContext(Dispatchers.Default) {
                val client = server.client().also { it.connect() }
                val routine = client.call(Api.Routines, AgentRef("work")).single { it.name == "Brief" }
                val ran = async(start = CoroutineStart.UNDISPATCHED) { client.events(Api.RoutineRan).first() }
                client.call(Api.RunRoutine, RoutineParams("work", routine.id))
                val result = ran.await()
                assertEquals(RunStatus.Ok, result.status)
                assertEquals("Two things are new", result.summary)
                val sent = server.prompts.inputs.single()
                assertEquals(routine.id, assertIs<RunKind.Routine>(sent.kind).turn.ref.id)
                val outcome = client.call(Api.History, HistoryParams("work", result.sessionId!!, 100)).map { it.entry }.filterIsInstance<SessionEntry.Outcome>().single()
                assertTrue(outcome.report)
                assertEquals("Two things are new", outcome.summary)
            }
        }
    }

    @Test
    fun aReflectionStartsAsAReflectionKindAndSavesMemory() = runBlocking {
        val server = TestServer.start(
            script = {
                on("Perform only the Memory stage") {
                    toolCall("memory", """{"target":"memory","operations":[{"action":"add","content":"Clear the cache after changing the SDK"}]}""")
                    reply("Saved")
                }
            },
            scenario = { agent("work") },
        )
        server.use {
            val agent = server.harness.store.agents().single { it.id == "work" }
            val result = server.harness.reflect(agent, "s1", listOf(SessionEntry.UserMessage("Remember to clear the cache after changing the SDK")))
            assertEquals(null, result.error)
            assertEquals(1, result.memory)
            assertTrue("Clear the cache after changing the SDK" in server.harness.docs.read("work", AgentDoc.Memory))
            assertIs<RunKind.Reflection>(server.prompts.inputs.single().kind)
            Unit
        }
    }
}
