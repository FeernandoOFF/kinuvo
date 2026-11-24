package kinuvo.app

import kinuvo.app.session.AgUiRunState
import kinuvo.app.session.ChatItem
import kinuvo.app.session.SessionData
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.ToolCallId
import kinuvo.protocol.agui.AgUiEvent
import kinuvo.protocol.agui.Interrupt
import kinuvo.protocol.agui.RunOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgUiRunStateTest {
    private fun fold(vararg events: AgUiEvent, committed: Set<String> = emptySet()) =
        events.fold(AgUiRunState()) { state, event -> state.on(event, committed) }

    private val started = AgUiEvent.RunStarted("thread", "run")
    private fun kinuvo(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject { put("kinuvo", buildJsonObject(build)) }

    @Test fun streamsTextAndUsesFinalTextOnEnd() {
        val state = fold(
            started,
            AgUiEvent.TextMessageStart("m"), AgUiEvent.TextMessageContent("m", "Hel"), AgUiEvent.TextMessageContent("m", "lo"),
        )
        assertEquals("Hello", state.messages.single().text)
        assertTrue(state.messages.single().open)
        val ended = state.on(AgUiEvent.TextMessageEnd("m", kinuvo { put("text", "Hello!") }))
        assertEquals("Hello!", ended.messages.single().text)
        assertFalse(ended.messages.single().open)
    }

    @Test fun keepsReasoningApartFromTextWithTheSameId() {
        val state = fold(started, AgUiEvent.ReasoningMessageStart("m"), AgUiEvent.ReasoningMessageContent("m", "hmm"), AgUiEvent.TextMessageContent("m", "hi"))
        assertEquals(listOf(true to "hmm", false to "hi"), state.messages.map { it.reasoning to it.text })
    }

    @Test fun foldsToolCallFromMetadataArgsWithoutDoubling() {
        val meta = kinuvo { put("args", "{\"a\":1}") }
        val state = fold(
            started,
            AgUiEvent.ToolCallStart("c", "shell", metadata = meta), AgUiEvent.ToolCallArgs("c", "{\"a\":1}", meta), AgUiEvent.ToolCallEnd("c", meta),
            AgUiEvent.ToolCallResult("result:c", "c", "ok", kinuvo { put("isError", true) }),
        )
        val call = state.tools.single()
        assertEquals("{\"a\":1}", call.args)
        assertEquals("ok", call.result)
        assertTrue(call.isError)
        assertFalse(call.open)
    }

    @Test fun concatenatesArgsDeltasWithoutMetadata() {
        val state = fold(started, AgUiEvent.ToolCallStart("c", "shell"), AgUiEvent.ToolCallArgs("c", "{\"a\""), AgUiEvent.ToolCallArgs("c", ":1}"))
        assertEquals("{\"a\":1}", state.tools.single().args)
    }

    @Test fun resultForCallOpenedInEarlierRunStillLands() {
        val state = fold(started, AgUiEvent.ToolCallResult("result:old", "old", "done"))
        assertEquals("old", state.tools.single().id)
        assertEquals("done", state.tools.single().result)
    }

    @Test fun skipsCommittedItems() {
        val state = fold(
            started,
            AgUiEvent.TextMessageContent("saved", "x"), AgUiEvent.ToolCallStart("call", "shell"), AgUiEvent.ToolCallResult("result:call", "call", "ok"),
            AgUiEvent.ActivitySnapshot("act", "kinuvo:warning", JsonObject(emptyMap())), AgUiEvent.TextMessageContent("live", "y"),
            committed = setOf("saved", "call", "act"),
        )
        assertEquals(listOf("live"), state.messages.map { it.id })
        assertTrue(state.tools.isEmpty())
        assertTrue(state.activities.isEmpty())
    }

    @Test fun activitySnapshotReplacesById() {
        val state = fold(
            started,
            AgUiEvent.ActivitySnapshot("a", "kinuvo:tool-progress", buildJsonObject { put("n", 1) }),
            AgUiEvent.ActivitySnapshot("a", "kinuvo:tool-progress", buildJsonObject { put("n", JsonPrimitive(2)) }),
        )
        assertEquals(buildJsonObject { put("n", 2) }, state.activities.single().content)
    }

    @Test fun interruptOutcomeKeepsInterruptsAndEndsTheRun() {
        val interrupt = Interrupt("i", "tool_call", toolCallId = "c")
        val state = fold(started, AgUiEvent.RunFinished("thread", "run", RunOutcome.Interrupted(listOf(interrupt))))
        assertFalse(state.running)
        assertEquals(listOf(interrupt), state.interrupts)
        assertEquals(state, state.on(AgUiEvent.TextMessageContent("late", "x")))
    }

    @Test fun cancelledAndErrorEndTheRun() {
        assertEquals(RunOutcome.Cancelled, fold(started, AgUiEvent.RunFinished("thread", "run", RunOutcome.Cancelled)).outcome)
        val failed = fold(started, AgUiEvent.RunError("boom"))
        assertEquals("boom", failed.error)
        assertNull(failed.outcome)
        assertFalse(failed.running)
    }

    @Test fun newRunResetsState() {
        val state = fold(started, AgUiEvent.TextMessageContent("m", "x"), AgUiEvent.RunFinished("thread", "run")).on(AgUiEvent.RunStarted("thread", "run2", parentRunId = "p"))
        assertEquals("run2", state.runId)
        assertEquals("p", state.parentRunId)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.running)
    }

    @Test fun committedEntryDropsItsItemAndLaterEventsForIt() {
        val streaming = fold(started, AgUiEvent.TextMessageContent("m", "Hel"), AgUiEvent.ToolCallStart("c", "shell"))
        val committed = streaming.committed(SessionEntry.AssistantMessage("Hello", "m")).committed(SessionEntry.ToolCall(ToolCallId("c"), "shell", "{}"))
        assertTrue(committed.messages.isEmpty())
        assertTrue(committed.tools.isEmpty())
        val later = committed.on(AgUiEvent.TextMessageContent("m", "lo")).on(AgUiEvent.ToolCallResult("result:c", "c", "ok"))
        assertTrue(later.messages.isEmpty())
        assertTrue(later.tools.isEmpty())
    }

    @Test fun replayedEventsSkipWhatTheSnapshotAlreadyHolds() {
        val replay = fold(started, AgUiEvent.TextMessageContent("m", "Hello"), AgUiEvent.TextMessageContent("n", "more"), committed = setOf("m"))
        assertEquals(listOf("n"), replay.messages.map { it.id })
    }

    @Test fun streamingMessagesShowInTheTranscriptUntilTheirEntryLands() {
        val run = fold(started, AgUiEvent.ReasoningMessageContent("r", "hm"), AgUiEvent.TextMessageContent("m", "Hel"))
        val data = SessionData(AgentId("a"), loaded = true, run = run)
        assertEquals(listOf("reasoning-r" to "hm", "text-m" to "Hel"), data.items.map { it.id to ((it as? ChatItem.Reasoning)?.text ?: (it as ChatItem.Assistant).text) })
        val saved = SessionData(AgentId("a"), entries = listOf(SessionEntry.AssistantMessage("Hello", "m")), loaded = true, run = run.committed(SessionEntry.AssistantMessage("Hello", "m")))
        assertEquals(listOf("text-m", "reasoning-r"), saved.items.map { it.id })
        assertEquals("Hello", (saved.items.first() as ChatItem.Assistant).text)
    }
}
