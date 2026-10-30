package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class SessionFeatureTest {
    @Test
    fun llmCallsAndEachResultAreDurableBeforeTheNextToolStarts() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        var requests = 0
        val model = scriptedModel { _, _ ->
            if (requests++ == 0) flowOf(StreamFrame.ToolCallComplete("first", "read", "{}"),
                StreamFrame.ToolCallComplete("second", "read", "{}"), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
        }
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("read", "Read")
            override suspend fun execute(args: JsonObject) = "OK"
        }
        AgentRun(home.agent, home.store, model, listOf(tool)).run(session.id, "go").collect { event ->
            if (event is AgentEvent.ToolCallStart) {
                val all = home.store.entries(home.agent.id, session.id)
                assertEquals(listOf("first", "second"), all.filterIsInstance<SessionEntry.ToolCall>().map { it.id })
                if (event.toolCallId == "second") assertEquals("first", all.filterIsInstance<SessionEntry.ToolResult>().single().id)
            }
        }
    }

    @Test
    fun cancellationAnswersEveryPersistedCallIncludingCallsNotStarted() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.ToolCallComplete("first", "read", "{}"),
            StreamFrame.ToolCallComplete("second", "read", "{}"), StreamFrame.End()) }
        AgentRun(home.agent, home.store, model, emptyList()).run(session.id, "go").first { it is AgentEvent.ToolCallStart }
        val results = home.store.entries(home.agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>()
        assertEquals(listOf("first", "second"), results.map { it.id })
        assertTrue(results.all { it.isError })
        assertEquals(listOf("Cancelled by user", "Not run: the turn was cancelled"), results.map { it.output })
    }

    @Test
    fun cancellingAnApprovalDoesNotDenyTheRestOfTheBatch() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        var executed = 0
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("read", "Read")
            override val identity = kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("test.tools"), "read")
            override suspend fun execute(args: JsonObject): String { executed++; return "OK" }
        }
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.ToolCallComplete("first", "read", "{}"),
            StreamFrame.ToolCallComplete("second", "read", "{}"), StreamFrame.End()) }
        val seen = mutableListOf<AgentEvent>()
        AgentRun(home.agent, home.store, model, listOf(tool),
            approvals = Approvals(ApprovalPolicy(home.agent, home.trustRules, Tolerance.Strict), ApprovalBroker()))
            .run(session.id, "go").first { seen += it; it is AgentEvent.ApprovalRequested }
        val results = home.store.entries(home.agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>()
        assertEquals(0, executed)
        assertEquals(listOf("Denied (stopped)", "Not run: the turn was cancelled"), results.map { it.output })
        assertEquals(listOf("first"), seen.filterIsInstance<AgentEvent.ToolCallStart>().map { it.toolCallId })
    }

    @Test
    fun toolCallsWithoutIdsReceiveStableGeneratedIds() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        var requests = 0
        val model = scriptedModel { _, _ ->
            if (requests++ == 0) flowOf(StreamFrame.ToolCallComplete(null, "read", "{}"), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
        }
        val events = AgentRun(home.agent, home.store, model, emptyList()).run(session.id, "go").toList()
        assertIs<AgentEvent.RunFinished>(events.last())
        val entries = home.store.entries(home.agent.id, session.id)
        val call = entries.filterIsInstance<SessionEntry.ToolCall>().single()
        assertTrue(call.id.isNotBlank())
        assertEquals(call.id, entries.filterIsInstance<SessionEntry.ToolResult>().single().id)
    }

    @Test
    fun unfinishedCallsAreSavedBeforeTheTerminalErrorReachesClients() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        val model = scriptedModel { _, _ -> flow {
            emit(StreamFrame.ToolCallComplete("first", "read", "{}"))
            emit(StreamFrame.ToolCallComplete("second", "read", "{}"))
            error("Broken stream")
        } }
        var failed = false
        val seen = mutableListOf<AgentEvent>()
        AgentRun(home.agent, home.store, model, emptyList()).run(session.id, "go").collect { event ->
            seen += event
            if (event is AgentEvent.RunError) {
                failed = true
                val results = home.store.entries(home.agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>()
                assertEquals(listOf("first", "second"), results.map { it.id })
                assertTrue(results.all { it.isError })
            }
        }
        assertTrue(failed)
        assertEquals(listOf("first", "second"), seen.filterIsInstance<AgentEvent.ToolCallStart>().map { it.toolCallId })
        assertEquals(listOf("first", "second"), seen.filterIsInstance<AgentEvent.ToolCallResult>().map { it.toolCallId })
    }

    @Test
    fun silentlyDroppedStreamsSavePartialTextAndReportFailure() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent.id, "")
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("partial")) }
        val events = AgentRun(home.agent, home.store, model, emptyList()).run(session.id, "go").toList()
        assertIs<AgentEvent.RunError>(events.last())
        val all = home.store.entries(home.agent.id, session.id)
        assertEquals("partial\n\n[interrupted]", all.filterIsInstance<SessionEntry.AssistantMessage>().single().text)
        assertFalse(all.filterIsInstance<SessionEntry.Measurement>().single().metrics.success)
    }
}
