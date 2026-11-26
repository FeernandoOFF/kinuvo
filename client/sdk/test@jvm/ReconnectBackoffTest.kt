package kinuvo.client

import arrow.resilience.Schedule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReconnectBackoffTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun networkReturnInterruptsBackoff() = runTest {
        var attempts = 0
        val client = KinuvoClient("local:retry", "Device", dispatcher = StandardTestDispatcher(testScheduler), connection = {
            attempts++
            error("offline")
        })
        val connecting = async { client.connect(retry = true) }
        try {
            runCurrent()
            assertEquals(1, attempts)
            client.reconnectNow()
            runCurrent()
            assertEquals(2, attempts)
        } finally { client.close(); connecting.cancel() }
    }

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
