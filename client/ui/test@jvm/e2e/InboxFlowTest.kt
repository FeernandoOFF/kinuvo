package kinuvo.ui.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.center
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe as touchSwipe
import androidx.compose.ui.test.performMouseInput
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class InboxFlowTest {
    private suspend fun AppDriver.chat(text: String) {
        tap("Message")
        type(text)
        tap("Send")
        await(reply)
    }

    private suspend fun AppDriver.hover(title: String) {
        await(title)
        ui.onAllNodes(label(title)).fetchSemanticsNodes().indices
            .map { ui.onAllNodes(label(title))[it] }
            .minBy { it.fetchSemanticsNode().boundsInRoot.left }
            .performMouseInput { moveTo(center) }
        settle()
    }

    @Test fun settlingMovesASessionOutOfTheInbox() = appTest("inbox", desktop = true) {
        pair()
        chat("Alpha plan")
        tap("New Session.*")
        chat("Beta plan")
        await("Alpha plan")
        screenshot("inbox")
        hover("Alpha plan")
        screenshot("hover")
        tap("Settle Alpha plan")
        await("Settled \\(1\\)")
        gone("Alpha plan")
        screenshot("settled-one")
        tap("Settled \\(1\\)")
        await("Alpha plan")
        screenshot("settled-open")
        hover("Alpha plan")
        tap("Unsettle Alpha plan")
        gone("Settled \\(1\\)")
        await("Alpha plan")
        tap("Open")
        gone("Alpha plan")
        await("Beta plan")
        screenshot("open-collapsed")
    }

    @Test fun swipingSettlesOnPhones() = appTest("inbox-phone") {
        pair()
        chat("Alpha plan")
        tap("New Session.*")
        chat("Beta plan")
        tap("Agents and sessions")
        await("Alpha plan")
        screenshot("drawer")
        ui.onAllNodes(label("Alpha plan")).fetchSemanticsNodes().indices.map { ui.onAllNodes(label("Alpha plan"))[it] }
            .maxBy { it.fetchSemanticsNode().boundsInRoot.top }
            .performTouchInput { touchSwipe(Offset(width * 0.1f, height / 2f), Offset(width * 0.9f, height / 2f), 300) }
        settle()
        await("Settled \\(1\\)")
        screenshot("swiped")
        tap("Settled \\(1\\)")
        await("Alpha plan")
        screenshot("settled-open")
        ui.onAllNodes(label("Beta plan")).fetchSemanticsNodes().indices.map { ui.onAllNodes(label("Beta plan"))[it] }
            .maxBy { it.fetchSemanticsNode().boundsInRoot.top }
            .performTouchInput { touchSwipe(Offset(width * 0.9f, height / 2f), Offset(width * 0.05f, height / 2f), 300) }
        settle()
        gone("Settled \\(1\\)")
        screenshot("swiped-left")
    }
}
