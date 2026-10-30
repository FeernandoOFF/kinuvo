package kinuvo.execution.api

import kinuvo.protocol.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.*

class ExecutionStateTest {
    @Test fun sinkRejectsStaleAndLostWritesAndCommitsBeforeCompletion() = runTest {
        val operations = mutableListOf<String>()
        val state = ExecutionState("s", generation = "g", sink = object : ExecutionSink {
            override suspend fun publish(event: AgentEvent) { operations += event::class.simpleName.orEmpty() }
            override suspend fun commit(entries: List<SessionEntry>) { operations += "commit" }
        })
        val sink = state.boundSink()
        assertFailsWith<IllegalStateException> { state.boundSink("old").commit(emptyList()) }
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running))
        val entry = SessionEntry.AssistantMessage("done", "m")
        sink.publish(AgentEvent.ExecutionEntryChanged(entry))
        assertFailsWith<IllegalStateException> { sink.publish(AgentEvent.RunFinished("turn")) }
        sink.commit(listOf(entry))
        sink.publish(AgentEvent.RunFinished("turn"))
        assertEquals(listOf("commit", "RunFinished"), operations.takeLast(2))
        assertTrue(state.live().entries.isEmpty())
        state.lost()
        assertFailsWith<IllegalStateException> { sink.commit(listOf(entry)) }
        assertFailsWith<IllegalStateException> { state.message(ExecutionMessage("late", "assistant", "late")) }
    }

    @Test fun initialIdleHasNoCompletionAndSameTextIsNotIdentity() = runTest {
        val events = mutableListOf<AgentEvent>()
        val state = ExecutionState("s", sink = object : ExecutionSink {
            override suspend fun publish(event: AgentEvent) { events += event }
            override suspend fun commit(entries: List<SessionEntry>) {}
        })
        state.update(ExecutionPhase.Idle)
        assertTrue(events.isEmpty())
        state.message(ExecutionMessage("local:one", "user", "same"))
        state.message(ExecutionMessage("source", "user", "same"))
        assertEquals(2, state.snapshot().messages.size)
    }

    @Test fun brokerMintsGenerationOwnedTokensAndClaimsOnce() = runTest {
        val state = ExecutionState("s")
        val broker = ExecutionBroker(state)
        val request = ExecutionRequest("wire", ExecutionRequestKind.Question, JsonNull, prompt = ExecutionPrompt.Unsupported("wire", "Unsupported"))
        val waiting = async { broker.ask(request) }
        runCurrent()
        val pending = state.snapshot().pending.single()
        assertNotEquals("wire", pending.id)
        assertEquals(pending.id, pending.prompt?.id)
        assertFalse(broker.answer("wire", JsonNull))
        assertTrue(broker.answer(pending.id, JsonNull))
        assertFalse(broker.answer(pending.id, JsonNull))
        assertEquals(JsonNull, waiting.await())
        val replacement = ExecutionBroker(ExecutionState("s"))
        assertFalse(replacement.answer(pending.id, JsonNull))
        broker.lost()
        assertFailsWith<IllegalStateException> { broker.ask(request) }
    }
}
