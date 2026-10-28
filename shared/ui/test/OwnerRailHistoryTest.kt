package kinuvo.ui

import androidx.compose.runtime.mutableStateListOf
import kinuvo.app.navigation.Route
import kinuvo.app.navigation.SettingsSection
import kinuvo.ui.navigation.History
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OwnerRailHistoryTest {
    private fun history(compact: Boolean, vararg routes: Route) =
        History(mutableStateListOf(*routes), mutableStateListOf()).apply { this.compact = compact }

    @Test fun taskRunReturnsToItsTaskThenHomeAtBothWidths() {
        for (compact in listOf(false, true)) {
            val h = history(compact, Route.Home)
            val task = Route.Task("review")
            val run = Route.Chat("echo", "20261022-160000-review")
            h.open(task)
            h.open(run)
            assertEquals(listOf(Route.Home, task, run), h.backStack.toList())
            h.back()
            assertEquals(task, h.backStack.last())
            h.back()
            assertEquals(Route.Home, h.backStack.last())
            assertFalse(h.canGoBack)
            h.forward()
            h.forward()
            assertEquals(run, h.backStack.last())
        }
    }

    @Test fun olderSessionFromHistoryOpensItsExactRunAndReturns() {
        for (compact in listOf(false, true)) {
            val latest = Route.Chat("echo", "20261026-210000-journal")
            val older = Route.Chat("echo", "20261025-210000-journal")
            val h = history(compact, Route.Home, latest)
            h.open(older)
            assertEquals(older, h.backStack.last())
            h.back()
            assertEquals(if (compact) Route.Home else latest, h.backStack.last())
        }
    }

    @Test fun boardOnCompactKeepsTheChatUnderneath() {
        val chat = Route.Chat("echo", "journal")
        val h = history(true, Route.Home, chat)
        h.open(Route.Board)
        assertEquals(listOf(Route.Home, chat, Route.Board), h.backStack.toList())
        h.back()
        assertEquals(chat, h.backStack.last())
    }

    @Test fun compactAgentSwitchReplacesTheChatAndKeepsHome() {
        val h = history(true, Route.Home, Route.Chat("echo", "journal"))
        val other = Route.Chat("hearth", "boiler")
        h.open(other)
        assertEquals(listOf(Route.Home, other), h.backStack.toList())
        h.back()
        assertEquals(Route.Home, h.backStack.last())
    }

    @Test fun settingsOverChatReturnsToTheExactSessionAtBothWidths() {
        for (compact in listOf(false, true)) {
            val chat = Route.Chat("echo", "journal")
            val h = history(compact, Route.Home, chat)
            h.open(Route.Settings("echo"), push = true)
            h.open(Route.Settings("echo", SettingsSection.Connection), push = false)
            h.back()
            assertEquals(chat, h.backStack.last())
        }
    }
}
