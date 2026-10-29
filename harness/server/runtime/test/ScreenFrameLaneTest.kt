package kinuvo.harness.server

import kinuvo.protocol.*
import kotlin.test.*

class ScreenFrameLaneTest {
    @Test fun subscriptionsShareADeviceBudgetAcrossConnections() {
        val budget = ScreenBudget()
        repeat(4) { budget.acquire("device") }
        assertFailsWith<IllegalStateException> { budget.acquire("device") }
        budget.acquire("other-device")
        budget.release("device")
        budget.acquire("device")
    }

    @Test fun floodRetainsOnlyLatestPerWatchWithinByteAndWatchBudgets() {
        val lane = ScreenFrameLane()
        val ref = ScreenRef("computer", "phone")
        repeat(10_000) { n -> lane.offer(ScreenFrame(ref, "watch", "epoch", n.toLong(), ScreenSample("image", 100, 100, "g"))) }
        assertEquals(9999L, lane.poll()?.sequence)
        assertNull(lane.poll())
        repeat(10) { n -> lane.offer(ScreenFrame(ref, "$n", "epoch", 1, ScreenSample("image", 100, 100, "g"))) }
        assertEquals(4, generateSequence { lane.poll() }.count())
        lane.offer(ScreenFrame(ref, "large", "epoch", 1, ScreenSample("x".repeat(1024 * 1024 + 1), 100, 100, "g")))
        assertNull(lane.poll())
    }
}
