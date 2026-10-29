package kinuvo.harness.core

import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ScreenViewTest {
    private val ref = ScreenRef("test", "one")
    private fun sample(image: String, geometry: String = "geometry") = ScreenSample(image, 100, 100, geometry)
    private fun ack(frame: ScreenFrame) = ScreenFrameAck(frame.subscription, frame.epoch, frame.sequence)

    @Test fun slowViewerKeepsOnlyLatestAndCannotAckOldEpoch() = runTest {
        val slow = ScreenView(ref, "slow", "epoch", 10)
        val fast = ScreenView(ref, "fast", "epoch")
        slow.offer(sample("one"))
        val first = assertNotNull(slow.poll())
        repeat(100) { slow.offer(sample("$it")); fast.offer(sample("$it")) }
        assertNull(slow.poll())
        assertEquals("99", fast.poll()?.sample?.image)
        assertFailsWith<IllegalStateException> { slow.ack(ack(first).copy(epoch = "previous")) }
        slow.ack(ack(first))
        slow.offer(sample("over-budget-image"))
        assertEquals("99", slow.poll()?.sample?.image)
        slow.close()
        assertNull(slow.poll())
        assertFailsWith<IllegalStateException> { slow.ack(ack(first)) }
    }

    @Test fun disconnectDuringBatchStopsRemainingActions() = runTest {
        val view = ScreenView(ref, "view", "epoch")
        val gate = ScreenGate()
        val held = gate.acquire("device", 0)
        view.offer(sample("image"))
        val frame = assertNotNull(view.poll())
        view.ack(ack(frame))
        val input = ScreenInput(ack(frame), "geometry", held.generation, 0,
            List(3) { ScreenAction(ScreenActionKind.Key, key = "enter") })
        var calls = 0
        val result = view.input("device", input, gate) { calls++; gate.disconnect("device") }
        assertEquals(1, calls)
        assertEquals(1, result.applied)
        assertTrue(gate.state.value.suspended)
    }

    @Test fun inputBindsHolderFrameGeometryAndConsumesFailedBatch() = runTest {
        val view = ScreenView(ref, "view", "epoch")
        val gate = ScreenGate()
        val held = gate.acquire("device", 0)
        view.offer(sample("image"))
        val frame = assertNotNull(view.poll())
        view.ack(ack(frame))
        val input = ScreenInput(ack(frame), "geometry", held.generation, 0,
            listOf(ScreenAction(ScreenActionKind.Tap, x = 10, y = 10), ScreenAction(ScreenActionKind.Type, text = "test")))
        assertFailsWith<IllegalStateException> { view.input("other", input, gate) {} }
        var calls = 0
        assertEquals(1, view.input("device", input, gate) { if (++calls == 2) error("driver failure") }.applied)
        assertFailsWith<IllegalArgumentException> { view.input("device", input, gate) {} }
        view.offer(sample("new", "rotated"))
        assertFailsWith<IllegalStateException> { view.input("device", input.copy(sequence = 1), gate) {} }
        gate.disconnect("device")
        assertFailsWith<IllegalStateException> { view.input("device", input.copy(sequence = 1), gate) {} }
    }
}
