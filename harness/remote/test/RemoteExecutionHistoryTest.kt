package kinuvo.harness.remote

import kinuvo.harness.ExecutionSink
import kinuvo.harness.ExecutionState
import kinuvo.protocol.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RemoteExecutionHistoryTest {
    @Test fun streamedNativeReplyCommitsBeforeCompletionAndAllowsAnotherTurn() = runTest {
        val saved = mutableListOf<SessionEntry>()
        val state = ExecutionState(SessionId("receiving"), sink = object : ExecutionSink {
            override suspend fun publish(event: AgentEvent) {}
            override suspend fun commit(entries: List<SessionEntry>) { saved += entries }
        })
        val sink = state.boundSink()
        repeat(2) { turn ->
            val messageId = "reply-$turn"
            state.begin()
            val streaming = ExecutionMessage("assistant:$messageId", "assistant", "Hello")
            val live = remoteEntries(listOf(streaming), state.generation).single()
            sink.publish(AgentEvent.ExecutionEntryChanged(live))
            val payload = ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), SessionEntry.AssistantMessage("Hello", messageId))
            val completed = remoteEntries(listOf(ExecutionMessage("assistant:$messageId", "assistant", "Hello", payload)), state.generation)
            assertEquals(live.identity(), completed.single().identity())
            sink.commit(completed)
            assertTrue(state.live().entries.isEmpty())
            sink.publish(AgentEvent.RunFinished(RunId("run-$turn")))
            sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
            assertEquals(ExecutionPhase.Idle, state.snapshot().phase)
        }
        assertEquals(2, saved.size)
        assertNotEquals(saved[0].identity(), saved[1].identity())
    }
}
