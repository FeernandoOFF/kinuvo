package kinuvo.app.connection

import kotlin.test.*

class LoopbackRequestTest {
    @Test fun pathOnlyForBareGet() {
        assertEquals("/auth/callback?state=s", loopbackRequestPath("GET /auth/callback?state=s HTTP/1.1"))
        assertNull(loopbackRequestPath("POST /auth/callback HTTP/1.1"))
        assertNull(loopbackRequestPath(""))
        assertNull(loopbackRequestPath("GET /auth/callback"))
        assertNull(loopbackRequestPath("GET http://example.com/auth/callback HTTP/1.1"))
        assertNull(loopbackRequestPath("GET //example.com/auth/callback HTTP/1.1"))
        assertNull(loopbackRequestPath("GET /auth/callback#ignored HTTP/1.1"))
    }

    @Test fun matchesOnlyOurPathAndState() {
        assertTrue(loopbackMatchesState("/auth/callback?state=expected", "http://127.0.0.1:1/auth/callback?state=expected&code=ok", "expected"))
        assertFalse(loopbackMatchesState("/auth/callback?state=wrong", "http://127.0.0.1:1/auth/callback?state=wrong", "expected"))
        assertFalse(loopbackMatchesState("/other?state=expected", "http://127.0.0.1:1/other?state=expected", "expected"))
    }

    @Test fun missingStateIsRejected() {
        assertFalse(loopbackMatchesState("/auth/callback?code=ok", "http://127.0.0.1:1/auth/callback?code=ok", null))
    }

    @Test fun responseLengthCountsUtf8Bytes() {
        assertTrue(loopbackHttpResponse(true, "é").contains("Content-Length: 2\r\n"))
    }

    @Test fun responseFramesTheBody() {
        val response = loopbackHttpResponse(ok = true, page = "hi", contentType = "text/html")
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("Content-Type: text/html\r\n"))
        assertTrue(response.contains("Content-Length: 2\r\n"))
        assertTrue(response.endsWith("\r\n\r\nhi"))
        assertTrue(loopbackHttpResponse(ok = false, page = "no").startsWith("HTTP/1.1 400 Bad Request\r\n"))
    }
}
