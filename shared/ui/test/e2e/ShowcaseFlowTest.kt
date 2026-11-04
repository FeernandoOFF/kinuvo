package kinuvo.ui.e2e

import kotlin.test.Test

/** Screens with dense, realistic data from the `showcase` profile, for reviewing layouts rather than behavior. */
class ShowcaseFlowTest {
    private suspend fun AppTest.connect() {
        openPairing("123456")
        await("Agents and sessions", 20_000)
    }

    @Test fun drawerWithManySessions() = appTest("showcase-drawer", "showcase") {
        connect()
        tap("Agents and sessions")
        await("Release notes review for the 2.0 launch.*")
        screenshot("sessions")
        tap("Older sessions")
        screenshot("older-sessions")
        tap("Research Assistant for Long-Running Investigations")
        await("Survey of on-device speech models")
        screenshot("long-agent-name")
    }

    @Test fun todayFullyPopulated() = appTest("showcase-today", "showcase") {
        connect()
        openHome()
        tap("Today")
        await("Needs you")
        screenshot("today")
        repeat(2) { swipe(50, 80, 50, 30) }
        screenshot("today-scrolled")
    }

    @Test fun boardWithEveryStatus() = appTest("showcase-board", "showcase") {
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

    @Test fun richChatTranscript() = appTest("showcase-chat", "showcase") {
        connect()
        tap("Agents and sessions")
        tap("Release notes review for the 2.0 launch.*")
        await(".*slack_post")
        screenshot("chat-end")
        repeat(2) { swipe(50, 25, 50, 80); screenshot("chat-up-${it + 1}") }
        tap("Browser")
        await("Kinuvo docs")
        requestsAtLeast("showcase/browser-watch")
        gone("Waiting for a fresh screen…")
        screenshot("browser-tab")
    }

    @Test fun knowledgeResultsAndLongNote() = appTest("showcase-knowledge", "showcase") {
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

    @Test fun routinesAndSkills() = appTest("showcase-routines", "showcase") {
        connect()
        openHome()
        tap("Routines")
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

    @Test fun computersWithSeveralScreens() = appTest("showcase-computers", "showcase") {
        connect()
        openHome()
        tap("Computers")
        await("Studio desktop")
        screenshot("computers")
        tap("Pixel 9")
        await("Take over")
        screenshot("phone")
    }
}
