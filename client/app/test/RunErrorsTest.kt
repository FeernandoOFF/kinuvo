package kinuvo.app

import kinuvo.app.session.runError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunErrorsTest {
    @Test fun offlineNamesTheHost() {
        val e = runError("""Error from client: codex
Message: Exception in http request: Error Domain=NSURLErrorDomain Code=-1009 "The Internet connection appears to be offline." NSErrorFailingURLKey=https://api.openai.com/v1/responses""")
        assertEquals("Couldn't reach api.openai.com", e.title)
        assertEquals(true, e.details?.contains("-1009"))
    }

    @Test fun rateLimit() = assertEquals("the model provider is rate limiting requests", runError("HTTP 429 Too Many Requests").title)

    @Test fun shortUnknownStaysAsIs() {
        val e = runError("Agent is busy")
        assertEquals("Agent is busy", e.title)
        assertNull(e.details)
    }
}
