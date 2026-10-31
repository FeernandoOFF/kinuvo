package kinuvo.harness.browser

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class BrowserDeadlineTest {
    @Test fun aBrowserTimeoutIsAnActionError() = runTest {
        val error = assertFailsWith<IllegalStateException> { browserDeadline(100) { delay(1000) } }
        assertTrue(error.message.orEmpty().contains("outcome may be unknown"))
    }
    @Test fun callerCancellationIsNotConvertedIntoAnActionError() = runTest {
        assertFailsWith<TimeoutCancellationException> { withTimeout(10) { browserDeadline(100) { delay(1000) } } }
    }
}
