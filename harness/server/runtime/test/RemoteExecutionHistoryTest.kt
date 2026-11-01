package kinuvo.harness.server

import kinuvo.execution.api.ExecutionSink
import kinuvo.execution.api.ExecutionState
import kinuvo.harness.server.execution.executionSnapshot
import kinuvo.harness.server.execution.remoteEntries
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RemoteExecutionHistoryTest {
    @Test fun streamedNativeReplyCommitsBeforeCompletionAndAllowsAnotherTurn() = runTest {
        val log = SessionLog(RuntimeFixture().store)
        val id = log.createSession("source", "").id
        val saved = mutableListOf<SessionEntry>()
        val state = ExecutionState("receiving", sink = object : ExecutionSink {
            override suspend fun publish(event: AgentEvent) {}
            override suspend fun commit(entries: List<SessionEntry>) { saved += entries }
        })
        val sink = state.boundSink()
        repeat(2) { turn ->
            val messageId = "reply-$turn"
            state.begin()
            log.publish("source", id, AgentEvent.RunStarted("run-$turn"))
            log.publish("source", id, AgentEvent.TextMessageContent(messageId, "Hello"))
            val streaming = log.executionSnapshot("source", id).messages.last()
            val live = remoteEntries(listOf(streaming), state.generation).single()
            sink.publish(AgentEvent.ExecutionEntryChanged(live))
            log.append("source", id, SessionEntry.AssistantMessage("Hello", messageId))
            log.publish("source", id, AgentEvent.RunFinished("run-$turn"))
            val completed = remoteEntries(listOf(log.executionSnapshot("source", id).messages.last()), state.generation)
            assertEquals(live.identity(), completed.single().identity())
            sink.commit(completed)
            assertTrue(state.live().entries.isEmpty())
            sink.publish(AgentEvent.RunFinished("run-$turn"))
            sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
            assertEquals(ExecutionPhase.Idle, state.snapshot().phase)
        }
        assertEquals(2, saved.size)
        assertNotEquals(saved[0].identity(), saved[1].identity())
    }
}
