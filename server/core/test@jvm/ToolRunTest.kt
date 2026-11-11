package kinuvo.server

import kinuvo.testing.server.AgentRun
import kinuvo.server.plugin.toolSpec
import kinuvo.harness.AgentConfig
import kinuvo.testing.harness.*

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.server.plugin.Tool
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.ToolRun
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.approval.approval
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.DenyRules
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.session.SessionEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ToolRunTest {
    @Test fun duplicateNamesCannotReplaceAnotherTool() = runTest {
        val session = home.store.createSession(home.agent.id, "")
        assertFailsWith<IllegalArgumentException> { AgentRun(home.agent, home.store, model, listOf(target, target)).run(session.id, "go").collect {} }
        assertEquals(1, home.store.entries(home.agent.id, session.id).size)
    }

    private val home = TestHome()
    private var calls = 0
    private val target = object : Tool {
        override val identity get() = kinuvo.protocol.plugin.ToolIdentity(kinuvo.protocol.plugin.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = toolSpec("change", "Change something")
        override val risk = Risk.Medium
        override suspend fun execute(args: JsonObject): String { calls++; return "changed" }
    }
    private val bridge = object : Tool {
        override val identity get() = kinuvo.protocol.plugin.ToolIdentity(kinuvo.protocol.plugin.PluginInstanceId("test.tools"), descriptor.name)
        override val descriptor = toolSpec("bridge", "Call another tool")
        override val risk = Risk.Low
        override suspend fun execute(args: JsonObject): String = error("Needs a run")
        override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
            assertFalse(run.tools.any { it.name == "bridge" })
            return run.call("change", "{}").output
        }
    }
    private val model = scriptedModel { prompt, _ ->
        val history = prompt.messages
        if (history.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("c1", "bridge", "{}"), StreamFrame.End())
        else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
    }

    @Test fun everyToolIsOnByDefault() {
        assertEquals(ToolNames.OPTIONAL, AgentConfig().tools)
    }

    @Test fun nestedCallsHonorTrustAndDenyRules() = runTest {
        val agent = home.agent
        val broker = ApprovalBroker()
        val runner = AgentRun(agent, home.store, model, listOf(bridge, target),
            approvals = Approvals(ApprovalPolicy(agent, home.trustRules), broker))
        val requests = mutableListOf<PendingInteraction.Approval>()
        repeat(2) {
            val session = home.store.createSession(agent.id, "")
            runner.run(session.id, "go").collect { event ->
                event.approval?.let { approval ->
                    requests += approval
                    broker.respond(approval.id, ApprovalDecision.AlwaysAllow)
                }
            }
        }
        assertEquals(listOf("change"), requests.map { it.toolCallName })
        assertEquals(listOf("c1"), requests.map { it.toolCallId.value })
        assertEquals(listOf(target.identity.key), home.trustRules.get(agent.id).trusted)
        assertEquals(2, calls)

        home.trustRules.update(agent.id) { it.copy(deny = DenyRules(tools = listOf(target.identity.key))) }
        val session = home.store.createSession(agent.id, "")
        runner.run(session.id, "go").collect { assertNull(it.approval) }
        assertEquals(2, calls)
        assertTrue(home.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>().single().output.contains("Deny rule"))
    }

    @Test fun stoppingNestedApprovalRecordsDenial() = runTest {
        val agent = home.agent
        val session = home.store.createSession(agent.id, "")
        AgentRun(agent, home.store, model, listOf(bridge, target),
            approvals = Approvals(ApprovalPolicy(agent, home.trustRules), ApprovalBroker()))
            .run(session.id, "go").first { it.approval != null }
        assertEquals(0, calls)
        assertEquals("Denied (stopped)", home.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>().single().output)
    }
}
