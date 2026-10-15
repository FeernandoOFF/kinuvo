package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.ToolRun
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.DenyRules
import kinuvo.protocol.Risk
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ToolRunTest {
    @Test fun duplicateNamesCannotReplaceAnotherTool() {
        assertFailsWith<IllegalArgumentException> { AgentRunner(home.agent, home.store, model, listOf(target, target)) }
    }

    private val home = TestHome()
    private var calls = 0
    private val target = object : Tool {
        override val identity get() = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = ToolDescriptor("change", "Change something")
        override val risk = Risk.Medium
        override suspend fun execute(args: JsonObject): String { calls++; return "changed" }
    }
    private val bridge = object : Tool {
        override val identity get() = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = ToolDescriptor("bridge", "Call another tool")
        override val risk = Risk.Low
        override suspend fun execute(args: JsonObject): String = error("Needs a run")
        override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
            assertFalse(run.tools.any { it.name == "bridge" })
            return run.call("change", "{}").output
        }
    }
    private val model = Model { _, history, _ ->
        if (history.none { it is SessionEntry.ToolResult }) flowOf(StreamFrame.ToolCallComplete("c1", "bridge", "{}"), StreamFrame.End())
        else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
    }

    @Test fun scriptsRequireOptIn() {
        assertTrue(ToolNames.RUN_CODE in ToolNames.OPTIONAL)
        assertFalse(ToolNames.RUN_CODE in AgentConfig().tools)
    }

    @Test fun nestedCallsHonorTrustAndDenyRules() = runTest {
        val agent = home.agent
        val broker = ApprovalBroker()
        val runner = AgentRunner(agent, home.store, model, listOf(bridge, target),
            approvals = Approvals(ApprovalPolicy(agent, home.trustRules), broker))
        val requests = mutableListOf<AgentEvent.ApprovalRequested>()
        repeat(2) {
            val session = home.store.createSession(agent, "")
            runner.run(session.id, "go").collect { event ->
                if (event is AgentEvent.ApprovalRequested) {
                    requests += event
                    broker.respond(event.approvalId, ApprovalDecision.AlwaysAllow)
                }
            }
        }
        assertEquals(listOf("change"), requests.map { it.toolCallName })
        assertEquals(listOf("c1"), requests.map { it.toolCallId })
        assertEquals(listOf(target.identity.key), home.trustRules.get(agent.id).trusted)
        assertEquals(2, calls)

        home.trustRules.update(agent.id) { it.copy(deny = DenyRules(tools = listOf(target.identity.key))) }
        val session = home.store.createSession(agent, "")
        runner.run(session.id, "go").collect { assertFalse(it is AgentEvent.ApprovalRequested) }
        assertEquals(2, calls)
        assertTrue(home.store.entries(agent, session.id).filterIsInstance<SessionEntry.ToolResult>().single().output.contains("Deny rule"))
    }

    @Test fun stoppingNestedApprovalRecordsDenial() = runTest {
        val agent = home.agent
        val session = home.store.createSession(agent, "")
        AgentRunner(agent, home.store, model, listOf(bridge, target),
            approvals = Approvals(ApprovalPolicy(agent, home.trustRules), ApprovalBroker()))
            .run(session.id, "go").first { it is AgentEvent.ApprovalRequested }
        assertEquals(0, calls)
        assertEquals("Denied (stopped)", home.store.entries(agent, session.id).filterIsInstance<SessionEntry.ToolResult>().single().output)
    }
}
