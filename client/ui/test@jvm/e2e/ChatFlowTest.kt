package kinuvo.ui.e2e

import kinuvo.protocol.Api
import kinuvo.protocol.session.SendParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ChatFlowTest {
    @Test fun pairsStartsASessionAndShowsTheReply() = appTest("chat", script = { on("Hello from Maestro") { reply(REPLY) } }) {
        screenshot("pairing")
        pair()
        screenshot("new-session")
        tap("Message")
        type("Hello from Maestro")
        tap("Send")
        await(REPLY)
        settle(1500)
        screenshot("reply")
        fun bounds(text: String) = ui.onAllNodes(label(text), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        // The title and the drawer's Session row name it too; the bubble is the one against the right edge.
        assertTrue(bounds("Hello from Maestro").maxBy { it.right }.bottom <= bounds(REPLY).single().top, "the reply shows below the message")
        requestsAtLeast(Api.Connect)
        requestsAtLeast(Api.Agents)
        requests(Api.CreateSession, 1)
        requests(Api.Send, 1)
        assertEquals(listOf("Hello from Maestro"), server.spy.params(Api.Send).map(SendParams::text))
    }
}
