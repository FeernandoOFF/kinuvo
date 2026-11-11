package kinuvo.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ErrorMessagesTest {
    @Test fun darwinErrorsBecomeTheirDescription() {
        val dump = """Exception in http request: Error Domain=NSURLErrorDomain Code=-1004 "Could not connect to the server." """ +
            """UserInfo={NSErrorFailingURLStringKey=ws://100.82.82.99:8791/ws, NSLocalizedDescription=Could not connect to the server.}"""
        assertEquals("Could not connect to the server", userMessage(dump))
    }

    @Test fun plainMessagesStay() {
        assertEquals("Connection refused", userMessage("Exception in http request: Connection refused"))
        assertEquals("Pairing code expired", userMessage("Pairing code expired"))
        assertNull(userMessage(" "))
        assertEquals("Couldn't reach the Server", IllegalStateException().userMessage)
    }
}
