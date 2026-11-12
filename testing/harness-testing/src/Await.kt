package kinuvo.testing.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Waits in real time for state that changes outside the test and has no flow to collect. */
suspend fun awaitCondition(
    description: String,
    timeout: Duration = 10.seconds,
    interval: Duration = 10.milliseconds,
    condition: suspend () -> Boolean,
) {
    val met = withContext(Dispatchers.Default) { withTimeoutOrNull(timeout) { while (!condition()) delay(interval); true } }
    if (met != true) throw AssertionError("Timed out after $timeout waiting for $description")
}

suspend fun <T : Any> awaitValue(
    description: String,
    timeout: Duration = 10.seconds,
    interval: Duration = 10.milliseconds,
    value: suspend () -> T?,
): T {
    var found: T? = null
    awaitCondition(description, timeout, interval) { value().also { found = it } != null }
    return checkNotNull(found)
}
