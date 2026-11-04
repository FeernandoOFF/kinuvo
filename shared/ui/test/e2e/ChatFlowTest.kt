package kinuvo.ui.e2e

import kotlin.test.Test

class ChatFlowTest {
    @Test fun pairsStartsASessionAndShowsTheReply() = appTest("chat", "chat") {
        screenshot("pairing")
        pair()
        screenshot("new-session")
        tap("Message")
        type("Hello from Maestro")
        tap("Send")
        await("Hello from WireMock")
        screenshot("reply")
        requestsAtLeast("ws/connect")
        requestsAtLeast("ws/agents")
        requests("ws/create-session", 1)
        requests("ws/chat-send", 1)
    }
}
