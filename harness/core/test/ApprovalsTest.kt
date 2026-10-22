package kinuvo.harness.core

import kinuvo.harness.testing.*

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.DenyRules
import kinuvo.protocol.TrustRules

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.protocol.Risk
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.string
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kinuvo.harness.core.store.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApprovalsTest {
    private val home = TestHome()
    private val store = home.store
    private val base = home.agent
    private fun agent(mode: String = "default") = base.copy(config = base.config.copy(approvals = mode))
    private fun policy(a: Agent) = ApprovalPolicy(a, home.trustRules)

    private class FakeTool(name: String, override val risk: Risk) : Tool {
        var calls = 0
        override val identity get() = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = ToolDescriptor(name, "fake")
        override suspend fun execute(args: JsonObject): String { calls++; return "ran" }
    }

    @Test
    fun mandatoryApprovalsIgnoreFullToleranceAndTrustRules() = runTest {
        val tool = object : Tool {
            override val identity = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("test.tools"), "private")
            override val descriptor = ToolDescriptor("private", "fake")
            override fun assess(args: JsonObject) = kinuvo.harness.core.tools.Assessment(listOf(
                kinuvo.harness.core.tools.Action(Risk.High, listOf("private"), alwaysAsk = true)))
            override suspend fun execute(args: JsonObject) = "ran"
        }
        home.trustRules.update(base.id) { it.copy(trusted = listOf(tool.identity.key)) }
        for (mode in Tolerance.ids) assertTrue(assertIs<Verdict.Ask>(policy(agent(mode)).decide(tool)).trustKeys.isEmpty())
    }

    @Test fun unknownRiskTolerancesAreRejected() {
        for (value in listOf("ask", "auto", "unknown")) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { Tolerance.of(value) }
        }
    }

    @Test
    fun computerActionsAreTrustedPerAppNeverForTheWholeTool() = runTest {
        val act = object : Tool {
            override val identity get() = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = ToolDescriptor(kinuvo.harness.core.tools.ToolNames.COMPUTER_ACT, "fake")
            override val kind = kinuvo.harness.core.tools.ToolKind.ComputerAction
            override fun assess(args: JsonObject) = kinuvo.harness.core.tools.Assessment(listOf(kinuvo.harness.core.tools.Action(
                Risk.High, listOf(descriptor.name, args.string("app")!!), exact = true)))
            override suspend fun execute(args: JsonObject) = "ran"
        }
        val policy = policy(agent("relaxed"))
        val chrome = """{"app":"com.android.chrome"}"""
        val ask = assertIs<Verdict.Ask>(policy.decide(act, chrome))
        assertEquals(listOf("${act.identity.key} com.android.chrome"), ask.trustKeys)
        home.trustRules.update(base.id) { it.copy(trusted = it.trusted + act.identity.key) }
        assertIs<Verdict.Ask>(policy.decide(act, chrome))
        policy.trust(act, ask.trustKeys)
        assertEquals(Verdict.Run, policy.decide(act, chrome))
        assertIs<Verdict.Ask>(policy.decide(act, """{"app":"com.example.bank"}"""))
    }

    @Test
    fun unsolicitedAndDuplicateResponses() = runTest {
        val broker = ApprovalBroker()
        assertFalse(broker.respond("not-registered", ApprovalDecision.AllowOnce))
        val pending = broker.register()
        assertFalse(broker.respond("not-registered", ApprovalDecision.AllowOnce))
        assertTrue(broker.respond(pending.id, ApprovalDecision.Deny("first")))
        assertFalse(broker.respond(pending.id, ApprovalDecision.AlwaysAllow))
        assertEquals(ApprovalDecision.Deny("first"), pending.await())
        pending.close()
        assertFalse(broker.respond(pending.id, ApprovalDecision.AllowOnce))
    }

    @Test
    fun concurrentRunsWithSameModelIdStayIndependent() = runTest {
        val tool = FakeTool("click", Risk.Medium)
        val model = scriptedModel { prompt, _ ->
            val history = prompt.messages
            if (history.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("same", "click", "{}"), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
        }
        val a = agent()
        val broker = ApprovalBroker()
        val runner = AgentRun(a, store, model, listOf(tool), approvals = Approvals(policy(a), broker))
        val sessions = List(2) { store.createSession(a, "").id }
        val requests = Channel<Pair<String, AgentEvent.ApprovalRequested>>(Channel.UNLIMITED)
        val jobs = sessions.map { id -> launch {
            runner.run(id, "go").collect { if (it is AgentEvent.ApprovalRequested) requests.send(id to it) }
        } }
        val (firstSession, first) = requests.receive()
        val (secondSession, second) = requests.receive()
        assertEquals("same", store.entries(a, firstSession).filterIsInstance<SessionEntry.ToolCall>().single().id)
        assertEquals("same", store.entries(a, secondSession).filterIsInstance<SessionEntry.ToolCall>().single().id)
        assertTrue(first.approvalId != second.approvalId)
        assertFalse(broker.respond("same", ApprovalDecision.AllowOnce))
        assertTrue(broker.respond(first.approvalId, ApprovalDecision.Deny()))
        assertTrue(broker.respond(second.approvalId, ApprovalDecision.AllowOnce))
        jobs.forEach { it.join() }
        assertTrue(store.entries(a, firstSession).filterIsInstance<SessionEntry.ToolResult>().single().isError)
        assertFalse(store.entries(a, secondSession).filterIsInstance<SessionEntry.ToolResult>().single().isError)
        assertEquals(1, tool.calls)
    }

    private suspend fun ApprovalPolicy.asks(tool: Tool, args: String = "{}") = decide(tool, args) is Verdict.Ask

    @Test
    fun serverDenyRulesWinOverAnAgentsTrust() = runTest {
        val risky = FakeTool("cua__click", Risk.Medium)
        val policy = policy(agent())
        policy.trust(risky, listOf("cua__click"))
        assertFalse(policy.asks(risky))
        home.trustRules.updateServer { it.copy(deny = kinuvo.protocol.DenyRules(tools = listOf("cua__click"))) }
        assertIs<Verdict.Block>(policy.decide(risky))
        assertTrue("cua__click" in home.files.serverConfig.readText())
    }

    @Test
    fun toleranceAndTrustRules() = runTest {
        val risky = FakeTool("cua__click", Risk.Medium)
        val safe = FakeTool("cua__screenshot", Risk.Low)
        val high = FakeTool("skill_manage", Risk.High)
        val policy = policy(agent())
        assertTrue(policy.asks(risky))
        assertFalse(policy.asks(safe))

        policy.trust(risky, listOf("cua__click"))
        policy.trust(risky, listOf("cua__click"))
        assertFalse(policy.asks(risky))
        assertEquals(TrustRules(listOf(risky.identity.key)), policy.rules())
        assertTrue("cua__click" in home.files.config(base.id).readText())

        assertTrue(policy(agent("strict")).asks(safe))
        assertFalse(policy(agent("relaxed")).asks(FakeTool("x", Risk.Medium)))
        assertTrue(policy(agent("relaxed")).asks(high))
        assertFalse(policy(agent("full")).asks(high))
    }

    private class FakeShell : Tool {
        override val kind = kinuvo.harness.core.tools.ToolKind.Shell
        override val identity get() = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = ToolDescriptor("shell", "fake")
        override fun assess(args: JsonObject) = ShellCommand.parse(args.string("command")!!).assess(listOf("/work"))
        override suspend fun execute(args: JsonObject) = "ran"
    }

    @Test
    fun trustedHighCommandsStillAskForRedirects() = runTest {
        val shell = FakeShell()
        fun cmd(c: String) = JsonObject(mapOf("command" to kotlinx.serialization.json.JsonPrimitive(c))).toString()
        val policy = policy(agent())
        val ask = assertIs<Verdict.Ask>(policy.decide(shell, cmd("rm -rf 'a b'")))
        policy.trust(shell, ask.trustKeys)
        assertIs<Verdict.Run>(policy.decide(shell, cmd("rm -rf 'a b'")))
        assertIs<Verdict.Ask>(policy.decide(shell, cmd("rm -rf a b")))
        assertEquals(emptyList(), assertIs<Verdict.Ask>(policy.decide(shell, cmd("rm -rf 'a b' > x"))).trustKeys)
        assertIs<Verdict.Run>(policy(agent("relaxed")).decide(shell, cmd("rm -rf 'a b' > x")))
    }

    @Test
    fun denyRulesWinOverEverything() = runTest {
        home.trustRules.update(base.id) { it.copy(trusted = listOf("cua__click"), deny = DenyRules(tools = listOf(FakeTool("cua__click", Risk.Low).identity.key))) }
        assertIs<Verdict.Block>(policy(agent("full")).decide(FakeTool("cua__click", Risk.Low)))
        assertIs<Verdict.Run>(policy(agent("full")).decide(FakeTool("other", Risk.High)))
    }

    /** Runs one turn where the model calls [tool] once, answering the Approval with [decision]. */
    private suspend fun kotlinx.coroutines.test.TestScope.turn(tool: FakeTool, decision: ApprovalDecision?): List<AgentEvent> {
        var step = 0
        val model = scriptedModel { _, _ ->
            if (step++ == 0) flowOf(StreamFrame.ToolCallComplete("c$step-${tool.calls}", tool.descriptor.name, "{}"), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
        }
        val broker = ApprovalBroker()
        val a = agent()
        val session = store.createSession(a, "")
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val job = launch { AgentRun(a, store, model, listOf(tool), approvals = Approvals(policy(a), broker)).run(session.id, "go").collect { events.send(it) } }
        val seen = mutableListOf<AgentEvent>()
        while (true) {
            val e = events.receive()
            seen += e
            if (e is AgentEvent.ApprovalRequested) {
                if (decision == null) { job.cancel(); break }
                broker.respond(e.approvalId, decision)
            }
            if (e is AgentEvent.RunFinished || e is AgentEvent.RunError) break
        }
        job.join()
        lastEntries = store.entries(a, session.id)
        return seen
    }

    private var lastEntries: List<SessionEntry> = emptyList()

    @Test
    fun allowOnceRunsAndAsksAgainNextTime() = runTest {
        val tool = FakeTool("cua__click", Risk.Medium)
        val first = turn(tool, ApprovalDecision.AllowOnce)
        assertTrue(first.any { it is AgentEvent.ApprovalResolved && it.allowed })
        assertEquals(1, tool.calls)
        assertTrue(turn(tool, ApprovalDecision.AllowOnce).any { it is AgentEvent.ApprovalRequested })
        assertEquals(2, tool.calls)
    }

    @Test
    fun alwaysAllowStopsAsking() = runTest {
        val tool = FakeTool("cua__click", Risk.Medium)
        turn(tool, ApprovalDecision.AlwaysAllow)
        val second = turn(tool, ApprovalDecision.Deny("should not be asked"))
        assertTrue(second.none { it is AgentEvent.ApprovalRequested })
        assertEquals(2, tool.calls)
    }

    @Test
    fun denyReturnsNoteToModel() = runTest {
        val tool = FakeTool("cua__click", Risk.Medium)
        turn(tool, ApprovalDecision.Deny("not now"))
        assertEquals(0, tool.calls)
        val result = lastEntries.filterIsInstance<SessionEntry.ToolResult>().single()
        assertTrue(result.isError)
        assertEquals("The user denied this call. Note: not now", result.output)
        assertIs<SessionEntry.AssistantMessage>(lastEntries.filterNot { it is SessionEntry.Measurement }.last())
    }

    @Test
    fun stopWhileWaitingRecordsDenial() = runTest {
        val tool = FakeTool("cua__click", Risk.Medium)
        turn(tool, null)
        assertEquals(0, tool.calls)
        assertEquals("Denied (stopped)", lastEntries.filterIsInstance<SessionEntry.ToolResult>().single().output)
    }

    @Test
    fun autoRiskToolsNeverAsk() = runTest {
        val tool = FakeTool("memory", Risk.Low)
        assertTrue(turn(tool, ApprovalDecision.Deny()).none { it is AgentEvent.ApprovalRequested })
        assertEquals(1, tool.calls)
    }
}
