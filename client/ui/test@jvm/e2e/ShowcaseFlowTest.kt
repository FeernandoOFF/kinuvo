package kinuvo.ui.e2e

import kinuvo.testing.server.FakeBrowser
import kinuvo.testing.server.FakeBrowserPlugin
import kinuvo.testing.server.FakeTool
import kinuvo.testing.server.FakeTools
import kinuvo.testing.server.computer
import kinuvo.testing.server.showcase
import kinuvo.protocol.Api
import kinuvo.protocol.browser.BrowserTab
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.test.Test
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.center
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick

/** Screens with dense, realistic data from the `showcase` scenario, for reviewing layouts rather than behavior. */
@OptIn(ExperimentalTestApi::class)
class ShowcaseFlowTest {
    private val plugins = listOf(
        FakeTools(
            FakeTool("shell", Risk.Medium, reason = "Books the flight under 120 EUR") { "booked" },
            FakeTool("slides") { awaitCancellation() },
            FakeTool("summarize") { delay(5000); "two clauses flagged" },
        ),
        FakeBrowserPlugin(FakeBrowser(listOf(BrowserTab("tab-docs", AgentId("e2e"), "Kinuvo docs", "https://docs.kinuvo.dev/guides/migrating-to-2-0")))),
    )

    private fun showcase(flow: String, desktop: Boolean = false, block: suspend AppTest.() -> Unit) = appTest(flow, plugins, desktop = desktop,
        script = {
            on("cheaper flight") { toolCall("shell"); reply("Booked") }
            on("planning deck") { toolCall("slides"); reply("Deck ready") }
            on("Summarize the contract") { toolCall("summarize"); reply("Two clauses need a lawyer.") }
        },
        scenario = { showcase(it) },
        block = block,
    )

    /** One Session waiting for an Approval and one running, which only a live Server can produce. */
    private suspend fun AppTest.live() {
        server.startSession(AgentId("e2e"), "Find a cheaper flight to Lisbon", title = "Find a cheaper flight to Lisbon")
        server.startSession(AgentId("e2e"), "Build the planning deck", title = "Build the planning deck")
    }

    private suspend fun AppDriver.connect() {
        openPairing(pairingCode)
        await("Agents and sessions", 20_000)
    }

    @Test fun drawerWithManySessions() = showcase("showcase-drawer") {
        live()
        connect()
        tap("Agents and sessions")
        await("Release notes review for the 2.0 launch.*")
        await("Find a cheaper flight to Lisbon")
        screenshot("sessions")
        tap("Open")
        screenshot("older-sessions")
        tap("Research Assistant for Long-Running Investigations")
        await("Survey of on-device speech models")
        screenshot("long-agent-name")
    }

    /** Every inbox state: needs you, working, unread (a run that ends while elsewhere), open, favorites pinned, settled. */
    private suspend fun AppTest.unread() {
        tap("Open")
        scrollTo("Contractor agreement review")
        tap("Contractor agreement review")
        tap("Message")
        type("Summarize the contract")
        tap("Send")
        scrollTo("Trip plans", up = true)
        tap("Trip plans")
        await("Unread", 15_000)
    }

    @Test fun inboxStatesOnDesktop() = showcase("showcase-inbox", desktop = true) {
        live()
        openPairing(pairingCode)
        await("Favorites", 20_000)
        unread()
        screenshot("inbox")
        listOf("Sort holiday photos", "Car insurance renewal").forEach { title ->
            await(title).performMouseInput { moveTo(center) }
            settle()
            if (title == "Sort holiday photos") screenshot("hover-settle")
            tap("Settle $title")
        }
        tap("Open")
        tap("Settled \\(2\\)")
        await("Sort holiday photos")
        screenshot("settled-open")
    }

    @Test fun inboxStatesOnPhone() = showcase("showcase-inbox-phone") {
        live()
        connect()
        tap("Agents and sessions")
        tap("Message.*")
        type("Summarize the contract")
        await("Send.*").performTouchInput { longClick() }
        settle()
        await("Unread", 15_000)
        screenshot("inbox")
    }

    @Test fun todayFullyPopulated() = showcase("showcase-today") {
        live()
        connect()
        openHome()
        tap("Today")
        await("Needs you")
        screenshot("today")
        repeat(2) { swipe(50, 80, 50, 30) }
        screenshot("today-scrolled")
    }

    @Test fun boardWithEveryStatus() = showcase("showcase-board") {
        connect()
        openHome()
        tap("Board")
        await("Renew passport before the summer trip.*")
        screenshot("board")
        repeat(3) { swipe(60, 60, 10, 60); screenshot("board-column-${it + 2}") }
        tap("In progress")
        tap("Prepare the quarterly planning slides")
        screenshot("task-with-runs")
    }

    @Test fun richChatTranscript() = showcase("showcase-chat") {
        connect()
        tap("Agents and sessions")
        tap("Release notes review for the 2.0 launch.*")
        await("End of Markdown review.")
        screenshot("chat-end")
        repeat(8) { if (!visible(".*slack_post")) swipe(50, 25, 50, 80) }
        await(".*slack_post")
        repeat(2) { swipe(50, 25, 50, 80); screenshot("chat-up-${it + 1}") }
        tap("Browser")
        await("Kinuvo docs")
        requestsAtLeast(Api.ScreenWatch)
        gone("Waiting for a fresh screen…")
        screenshot("browser-tab")
    }

    @Test fun knowledgeResultsAndLongNote() = showcase("showcase-knowledge") {
        connect()
        openHome()
        tap("Knowledge")
        tap("Search people, projects, places…")
        type("Kinuvo")
        await("Kinuvo sync without a Server")
        screenshot("results")
        tap("project")
        await("Properties")
        screenshot("note")
        scrollTo("Linked notes")
        swipe(50, 80, 50, 30)
        screenshot("note-links")
    }

    @Test fun routinesAndSkills() = showcase("showcase-routines") {
        connect()
        openHome()
        tap("Routines & Workflows")
        await("Inbox triage")
        screenshot("routines")
        tap("Home and agents")
        tapAbove("E2E Agent", "New agent")
        tap("Release notes review for the 2.0 launch.*")
        tap("E2E Agent settings")
        scrollTo("Skills")
        tap("Skills")
        await("release-notes")
        screenshot("skills")
    }

    @Test fun computersWithSeveralScreens() = showcase("showcase-computers") {
        connect()
        server.computer("Pixel 9")
        server.computer("Studio desktop")
        openHome()
        tap("Computers")
        await("Studio desktop")
        screenshot("computers")
        tap("Pixel 9")
        await("Take over")
        screenshot("phone")
    }
}
