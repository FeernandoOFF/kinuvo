package kinuvo.server

import kotlin.test.*

class RequestRateLimitTest {
    @Test fun windowsAreSharedByIdentityAndExpire() {
        var time = 0L
        val limit = RequestRateLimit { time }
        assertTrue(limit.accept("device", 2))
        assertTrue(limit.accept("device", 2))
        assertFalse(limit.accept("device", 2))
        assertTrue(limit.accept("other", 2))
        time = 60_000
        assertTrue(limit.accept("device", 2))
    }

    @Test fun boundsDistinctIdentitiesAndRecoversAfterWindow() {
        var time = 0L
        val limit = RequestRateLimit { time }
        repeat(4096) { assertTrue(limit.accept("source-$it", 1)) }
        assertFalse(limit.accept("overflow", 1))
        time = 60_000
        assertTrue(limit.accept("overflow", 1))
    }
}
