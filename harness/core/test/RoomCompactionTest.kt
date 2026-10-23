package kinuvo.harness.core

import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.RequestKind
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RoomCompactionTest {
    @Test fun measuredRoomBudgetStopsBeforeTheSecondCompactionProviderRequest() = runTest {
        val home = TestHome()
        val agent = home.agent
        val session = home.store.createSession(agent, "stable", roomExecution = true)
        home.store.append(agent, session.id, SessionEntry.UserMessage("Earlier private lookup"))
        home.store.append(agent, session.id, SessionEntry.ToolCall("old", "read", "{}"))
        home.store.append(agent, session.id, SessionEntry.ToolResult("old", "read", "PRIVATE_HISTORY".repeat(4000)))
        var providerRequests = 0
        var summaries = 0
        val model = scriptedModel { prompt, _ ->
            providerRequests++
            if (prompt.messages.last().textContent().contains("Create a comprehensive summary")) summaries++
            flowOf(StreamFrame.TextDelta("Earlier lookup summarized."),
                StreamFrame.End(metaInfo = ResponseMetaInfo.Empty.copy(inputTokensCount = 15, outputTokensCount = 5)))
        }
        val room = RoomRun {
            val used = home.store.entries(agent, session.id).filterIsInstance<SessionEntry.Measurement>()
                .sumOf { (it.metrics.inputTokens ?: 0) + (it.metrics.outputTokens ?: 0) }
            check(used < 20) { "Room measured token budget reached" }
        }
        val events = AgentRun(agent, home.store, model, emptyList(), contextTokens = 4096, room = room).run(session.id, "Next question").toList()
        assertEquals(1, providerRequests)
        assertEquals(1, summaries)
        assertTrue(assertIs<AgentEvent.RunError>(events.last()).message.contains("token budget"))
        val entries = home.store.entries(agent, session.id)
        val metrics = entries.filterIsInstance<SessionEntry.Measurement>().single().metrics
        assertEquals(RequestKind.Compaction, metrics.kind)
        assertTrue(metrics.success)
        assertEquals(15, metrics.inputTokens)
        assertEquals(5, metrics.outputTokens)
        assertTrue(entries.none { it is SessionEntry.Compaction })
        assertNull(room.outcome)
    }
}
