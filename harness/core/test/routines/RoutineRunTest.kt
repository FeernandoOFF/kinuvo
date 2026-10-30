package kinuvo.harness.core.routines

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentRun
import kinuvo.harness.core.ApprovalBroker
import kinuvo.harness.core.ApprovalPolicy
import kinuvo.harness.core.Approvals
import kinuvo.harness.core.TestHome
import kinuvo.harness.core.Tolerance
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.testing.scriptedModel
import kinuvo.harness.testing.toolResults
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.Risk
import kinuvo.protocol.Routine
import kinuvo.protocol.RunStatus
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ToolIdentity
import kinuvo.protocol.Trigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class RoutineRunTest {
    private val home = TestHome()
    private val agent = home.agent
    private val store = home.store
    private val routine = Routine("watch", agent.id, "Watch bills", "Check my bills", Trigger.Every(30), "UTC")
    private val notified = mutableListOf<SessionEntry.Outcome>()
    private val prompts = mutableListOf<List<Message>>()

    private fun run(base: Routine = routine) = RoutineRun(base, { 1_790_000_000_000L }) { notified += it }

    private fun RoutineReportTool.bound(): Tool = object : Tool by this { override val identity = ToolIdentity(PluginInstanceId("kinuvo.core"), descriptor.name) }

    private class Deploy(risk: Risk = Risk.High) : Tool {
        var calls = 0
        override val risk = risk
        override val identity = ToolIdentity(PluginInstanceId("test.tools"), "deploy")
        override val descriptor = ToolDescriptor("deploy", "Deploys")
        override suspend fun execute(args: JsonObject): String { calls++; return "deployed" }
    }

    /** Calls each of [calls] in turn, then replies [reply]. */
    private fun model(vararg calls: Pair<String, String>, reply: String = "All checked.") = scriptedModel { prompt, _ ->
        prompts += prompt.messages
        val next = calls.getOrNull(prompt.messages.toolResults.size)
        if (next == null) flowOf(StreamFrame.TextDelta(reply), StreamFrame.End())
        else flowOf(StreamFrame.ToolCallComplete("c${prompt.messages.toolResults.size}", next.first, next.second), StreamFrame.End())
    }

    private suspend fun execute(run: RoutineRun, model: kinuvo.harness.core.AgentModel, tools: List<Tool> = emptyList(), approvals: Approvals = Approvals.none()): List<AgentEvent> {
        val session = store.createSession(agent.id, "", run.ref)
        return AgentRun(agent, store, model, tools + RoutineReportTool(run).bound(), approvals = approvals, routine = run).run(session.id, run.routine.prompt).toList()
            .also { sessionId = session.id }
    }

    private var sessionId = ""
    private suspend fun entries() = store.entries(agent.id, sessionId)

    @Test
    fun aReportIsSavedWithTheSessionAndNotified() = runTest {
        val run = run()
        val events = execute(run, model("routine_report" to """{"outcome":"report","summary":"Rent is due tomorrow"}""", reply = "Flagged the rent bill."))

        assertIs<AgentEvent.RunFinished>(events.last())
        assertEquals(listOf(SessionEntry.Outcome(true, "Rent is due tomorrow")), notified)
        val entries = entries()
        assertEquals(SessionEntry.Outcome(true, "Rent is due tomorrow"), entries.last())
        assertEquals("Flagged the rent bill.", entries.filterIsInstance<SessionEntry.AssistantMessage>().last().text)
        val sent = entries.filterIsInstance<SessionEntry.UserMessage>().single()
        assertEquals(run.ref, sent.routine)
        assertTrue("Check my bills" in sent.text && "(UTC), every 30m" in sent.text)
        assertTrue(store.session(agent.id, sessionId).inChats)
    }

    @Test
    fun nothingNewSendsNoNotificationButTheSessionKeepsTheRun() = runTest {
        val run = run()
        val events = execute(run, model("routine_report" to """{"outcome":"nothing_new"}""", reply = "Nothing changed."))

        assertIs<AgentEvent.RunFinished>(events.last())
        assertTrue(notified.isEmpty())
        val entries = entries()
        assertEquals(SessionEntry.Outcome(false), entries.last())
        assertEquals("Nothing changed.", entries.filterIsInstance<SessionEntry.AssistantMessage>().last().text)
        assertEquals(false, store.session(agent.id, sessionId).inChats)
    }

    @Test
    fun aRunThatNeverReportsTellsTheUserItsReply() = runTest {
        execute(run(), model(reply = "Paid the invoice."))
        assertEquals(listOf(SessionEntry.Outcome(true, "Paid the invoice.")), notified)
    }

    @Test
    fun aReportNeedsASummary() = runTest {
        val run = run()
        execute(run, model("routine_report" to """{"outcome":"report"}""", "routine_report" to """{"outcome":"nothing_new"}"""))
        assertEquals(listOf("Error: Give a summary to report", "Recorded."), entries().filterIsInstance<SessionEntry.ToolResult>().map { it.output })
        assertTrue(notified.isEmpty())
    }

    @Test
    fun theNextRunSeesHowThePreviousOneEnded() = runTest {
        suspend fun firstMessage(previous: Routine): String {
            prompts.clear()
            execute(run(previous), model(reply = "ok"))
            return prompts.first().filterIsInstance<Message.User>().first().textContent()
        }
        val at = 1_789_990_000_000L
        val reported = firstMessage(routine.copy(lastRunAt = at, lastStatus = RunStatus.Ok, lastReport = "Rent is due tomorrow"))
        assertTrue("Last run, 2026-09-21 11:" in reported && "told the user: Rent is due tomorrow." in reported, reported)
        assertTrue("found nothing new." in firstMessage(routine.copy(lastRunAt = at, lastStatus = RunStatus.Silent)))
        assertTrue("failed: timeout." in firstMessage(routine.copy(lastRunAt = at, lastStatus = RunStatus.Error, lastError = "timeout")))
        assertTrue("Last run" !in firstMessage(routine))
    }

    @Test
    fun anApprovalNobodyAnswersIsSkippedAndTheRunMentionsIt() = runTest {
        val run = run()
        val deploy = Deploy()
        val approvals = Approvals(ApprovalPolicy(agent, home.trustRules, Tolerance.Relaxed), ApprovalBroker(), wait = 50.milliseconds, skipped = { run.skipped += it })
        val events = execute(run, model("deploy" to "{}", "routine_report" to """{"outcome":"nothing_new"}""", reply = "Done."), listOf(deploy), approvals)

        assertIs<AgentEvent.RunFinished>(events.last(), events.last().toString())
        assertEquals(0, deploy.calls)
        assertTrue(events.any { it is AgentEvent.ApprovalRequested })
        assertEquals(AgentEvent.ApprovalResolved("c0", false), events.filterIsInstance<AgentEvent.ApprovalResolved>().single())
        val result = entries().filterIsInstance<SessionEntry.ToolResult>().first()
        assertTrue(result.isError && result.output.startsWith("No answer within") && result.output.endsWith("; skipped"), result.output)
        assertEquals(listOf(SessionEntry.Outcome(true, "Skipped, no Approval: deploy")), notified)
    }

    @Test
    fun anApprovalAnsweredInTimeRunsTheCall() = runTest {
        val run = run()
        val deploy = Deploy()
        val broker = ApprovalBroker()
        val approvals = Approvals(ApprovalPolicy(agent, home.trustRules, Tolerance.Relaxed), broker, wait = 30.minutes, skipped = { run.skipped += it })
        val session = store.createSession(agent.id, "", run.ref)
        val model = model("deploy" to "{}", "routine_report" to """{"outcome":"nothing_new"}""")
        AgentRun(agent, store, model, listOf(deploy, RoutineReportTool(run).bound()), approvals = approvals, routine = run).run(session.id, "go").collect {
            if (it is AgentEvent.ApprovalRequested) assertTrue(broker.respond(it.approvalId, ApprovalDecision.AllowOnce))
        }

        assertEquals(1, deploy.calls)
        assertTrue(run.skipped.isEmpty())
        assertTrue(notified.isEmpty())
        assertEquals(listOf("deployed", "Recorded."), store.entries(agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>().map { it.output })
    }

    @Test
    fun stoppingARunRecordsNoOutcomeAndClosesItsCalls() = runTest {
        val run = run()
        val session = store.createSession(agent.id, "", run.ref)
        val slow = object : Tool {
            override val descriptor = ToolDescriptor("slow", "never finishes")
            override suspend fun execute(args: JsonObject): String = kotlinx.coroutines.awaitCancellation()
        }
        val stuck = scriptedModel { _, _ -> flowOf(StreamFrame.ToolCallComplete("c1", "slow", "{}"), StreamFrame.End()) }
        AgentRun(agent, store, stuck, listOf(slow, RoutineReportTool(run)), routine = run).run(session.id, "go").first { it is AgentEvent.ToolCallStart }

        assertEquals(SessionEntry.ToolResult("c1", "slow", "Cancelled by user", isError = true), store.entries(agent.id, session.id).last())
        assertTrue(store.entries(agent.id, session.id).none { it is SessionEntry.Outcome })
        assertTrue(notified.isEmpty())
        assertNull(run.outcome)
    }
}
