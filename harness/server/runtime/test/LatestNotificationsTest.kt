package kinuvo.harness.server.execution

import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LatestNotificationsTest {
    @Test fun slowDeliveryRetainsNewestTranscriptPendingAndFinalState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<ExecutionSnapshot>()
        val notifications = LatestNotifications<String, ExecutionSnapshot>(backgroundScope) { _, value ->
            gate.await()
            delivered.add(value)
        }
        val history = List(2000) { ExecutionMessage("m$it", "assistant", "history $it") }
        notifications.offer("s", ExecutionSnapshot("s", messages = history))
        runCurrent()
        advanceTimeBy(33)
        runCurrent()
        repeat(5000) { token ->
            notifications.offer("s", ExecutionSnapshot("s", ExecutionPhase.Running, history + ExecutionMessage("stream", "assistant", "$token")))
        }
        val pending = ExecutionSnapshot("s", ExecutionPhase.RequiresAction, history + ExecutionMessage("stream", "assistant", "4999"), listOf(ExecutionRequest("approval", ExecutionRequestKind.Approval, kotlinx.serialization.json.JsonNull)))
        notifications.offer("s", pending)
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(33)
        runCurrent()
        assertEquals(2, delivered.size)
        assertEquals(pending, delivered.last())
        assertEquals(2001, transcript(delivered.last()).size)
        assertEquals("stream", (transcript(delivered.last()).last() as SessionEntry.AssistantMessage).messageId)
        val final = pending.copy(phase = ExecutionPhase.Idle, pending = emptyList())
        notifications.offer("s", final)
        advanceTimeBy(33)
        runCurrent()
        assertEquals(final, delivered.last())
        notifications.close()
    }
}
