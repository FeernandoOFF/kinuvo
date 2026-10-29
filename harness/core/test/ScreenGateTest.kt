package kinuvo.harness.core

import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ScreenGateTest {
    @Test fun reserveDrainsAndRejectsQueuedAndStaleActions() = runTest {
        val gate = ScreenGate()
        val running = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val action = launch { gate.agent { running.complete(Unit); finish.await() } }
        running.await()
        val acquire = async { gate.acquire("device", 0) }
        testScheduler.runCurrent()
        assertTrue(gate.state.value.draining)
        val queued = async { runCatching { gate.agent(0) { fail("queued action ran") } } }
        finish.complete(Unit)
        action.join()
        val held = acquire.await()
        assertTrue(queued.await().isFailure)
        gate.input("device", held.generation) {}
        gate.disconnect("device")
        assertFailsWith<IllegalStateException> { gate.input("device", held.generation) {} }
        assertFailsWith<IllegalStateException> { gate.agent {} }
        assertFailsWith<IllegalStateException> { gate.release("other", gate.state.value.generation) }
        gate.release("device", gate.state.value.generation)
        gate.agent {}
    }

    @Test fun drainTimeoutNeverGrantsAndRestoredHoldStaysSuspended() = runTest {
        val gate = ScreenGate()
        val started = CompletableDeferred<Unit>()
        val job = launch { gate.agent { started.complete(Unit); delay(1000) } }
        started.await()
        assertFailsWith<TimeoutCancellationException> { gate.acquire("device", 0, 10) }
        assertNull(gate.state.value.holder)
        assertFalse(gate.state.value.draining)
        job.join()
        val recovered = ScreenGate(ScreenControl(5, "old-device", suspended = true))
        assertFailsWith<IllegalStateException> { recovered.agent {} }
        val held = recovered.acquire("new-device", 5)
        assertEquals(6, held.generation)
        assertFailsWith<IllegalStateException> { recovered.release("old-device", 5) }
        recovered.input("new-device", 6) {}
    }
}
