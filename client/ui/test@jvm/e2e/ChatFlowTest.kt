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

    @Test fun firstMessageOfANewSessionShowsWorkingUntilTheReply() = appTest("chat-new-session-working",
        script = { on("Hello from Maestro") { reply(REPLY) } },
    ) {
        pair()
        tap("Message")
        type("Hello from Maestro")
        tap("Send")
        await("Working…")
        await(REPLY)
        gone("Working…")
        requests(Api.CreateSession, 1)
        requests(Api.Send, 1)
    }

    @Test fun secondSendInAnOpenSessionShowsItsOwnReply() = appTest("chat-second-send",
        script = { on("Hey") { reply("Hi there") }; on("Who are you") { reply("I'm the E2E Agent") } },
        modelDelayMillis = 250,
    ) {
        pair()
        tap("Message")
        type("Hey")
        tap("Send")
        await("Working…")
        await("Hi there")
        gone("Working…")
        settle(500)
        tap("Message")
        type("Who are you")
        tap("Send")
        await("Working…")
        await("I'm the E2E Agent")
        gone("Working…")
        settle(500)
        screenshot("second-reply")
        requests(Api.Send, 2)
        assertEquals(listOf("Hey", "Who are you"), server.spy.params(Api.Send).map(SendParams::text))
    }
}
