package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class SessionFeatureTest {
    @Test
    fun llmCallsAndEachResultAreDurableBeforeTheNextToolStarts() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent, "")
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
                val all = home.store.entries(home.agent, session.id)
                assertEquals(listOf("first", "second"), all.filterIsInstance<SessionEntry.ToolCall>().map { it.id })
                if (event.toolCallId == "second") assertEquals("first", all.filterIsInstance<SessionEntry.ToolResult>().single().id)
            }
        }
    }

    @Test
    fun cancellationAnswersEveryPersistedCallIncludingCallsNotStarted() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent, "")
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.ToolCallComplete("first", "read", "{}"),
            StreamFrame.ToolCallComplete("second", "read", "{}"), StreamFrame.End()) }
        AgentRun(home.agent, home.store, model, emptyList()).run(session.id, "go").first { it is AgentEvent.ToolCallStart }
        val results = home.store.entries(home.agent, session.id).filterIsInstance<SessionEntry.ToolResult>()
        assertEquals(listOf("first", "second"), results.map { it.id })
        assertTrue(results.all { it.isError && it.output == "Cancelled by user" })
    }

    @Test
    fun unfinishedCallsAreSavedBeforeTheTerminalErrorReachesClients() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent, "")
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.ToolCallComplete("first", "read", "{}"),
            StreamFrame.ToolCallComplete("second", "read", "{}"), StreamFrame.End()) }
        var failed = false
        AgentRun(home.agent, home.store, model, emptyList(), maxAgentIterations = 3).run(session.id, "go").collect { event ->
            if (event is AgentEvent.RunError) {
                failed = true
                val results = home.store.entries(home.agent, session.id).filterIsInstance<SessionEntry.ToolResult>()
                assertEquals(listOf("first", "second"), results.map { it.id })
                assertTrue(results.all { it.isError })
            }
        }
        assertTrue(failed)
    }

    @Test
    fun silentlyDroppedStreamsSavePartialTextAndReportFailure() = runTest {
        val home = TestHome()
        val session = home.store.createSession(home.agent, "")
        val model = scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("partial")) }
        val events = AgentRun(home.agent, home.store, model, emptyList()).run(session.id, "go").toList()
        assertIs<AgentEvent.RunError>(events.last())
        val all = home.store.entries(home.agent, session.id)
        assertEquals("partial\n\n[interrupted]", all.filterIsInstance<SessionEntry.AssistantMessage>().single().text)
        assertFalse(all.filterIsInstance<SessionEntry.Measurement>().single().metrics.success)
    }
}
