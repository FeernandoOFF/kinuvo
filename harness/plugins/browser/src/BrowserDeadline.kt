package kinuvo.harness.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

suspend fun <T> browserDeadline(milliseconds: Long, block: suspend CoroutineScope.() -> T): T = try {
    withTimeout(milliseconds, block)
} catch (error: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw IllegalStateException("Browser timed out; outcome may be unknown. Capture and inspect before continuing.", error)
}
