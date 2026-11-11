package kinuvo.ui.e2e

import kinuvo.protocol.Api
import kinuvo.protocol.session.SendParams
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatFlowTest {
    @Test fun pairsStartsASessionAndShowsTheReply() = appTest("chat", script = { on("Hello from Maestro") { reply(REPLY) } }) {
        screenshot("pairing")
        pair()
        screenshot("new-session")
        tap("Message")
        type("Hello from Maestro")
        tap("Send")
        await(REPLY)
        screenshot("reply")
        requestsAtLeast(Api.Connect)
        requestsAtLeast(Api.Agents)
        requests(Api.CreateSession, 1)
        requests(Api.Send, 1)
        assertEquals(listOf("Hello from Maestro"), server.spy.params(Api.Send).map(SendParams::text))
    }
}
