package kinuvo.client

import arrow.resilience.Schedule
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReconnectBackoffTest {
    @Test fun waitsGrowAndStayUnderTenSeconds() = runBlocking {
        var step = ReconnectBackoff.step
        val waits = mutableListOf<Duration>()
        repeat(12) {
            val next = step(Unit) as Schedule.Decision.Continue
            waits += reconnectWait(next.delay)
            step = next.step
        }
        assertTrue(waits.first() in 250.milliseconds..500.milliseconds, "first wait ${waits.first()}")
        assertTrue(waits.all { it <= 10.seconds }, "waits $waits")
        assertTrue(waits.last() >= 5.seconds, "waits $waits")
        assertTrue(waits[3] > waits[0], "waits $waits")
    }
}
