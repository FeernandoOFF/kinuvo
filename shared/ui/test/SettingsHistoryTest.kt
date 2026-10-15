package kinuvo.ui

import kinuvo.app.navigation.*
import kinuvo.app.connection.*
import kinuvo.app.device.*
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kinuvo.ui.navigation.*

import androidx.compose.runtime.mutableStateListOf
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsHistoryTest {
    private val list = Route.Settings("main", SettingsSection.Routines)
    private val item = list.copy(item = "heartbeat")
    private val form = item.copy(editing = true)

    private fun history(vararg routes: Route) = History(mutableStateListOf(*routes), mutableStateListOf())

    @Test fun movingAroundSettingsInPlaceGoesBackToTheChat() {
        val chat = Route.Chat("main", "s1")
        val h = history(chat)
        h.open(Route.Settings("main"), push = true)
        listOf(list, item, form).forEach { h.open(it, push = false) }
        h.back()
        assertEquals(chat, h.backStack.last())
    }

    @Test fun pushedLevelsGoBackOneAtATime() {
        val h = history(Route.Settings("main"))
        listOf(list, item, form).forEach { h.open(it, push = true) }
        h.back()
        assertEquals(item, h.backStack.last())
    }

    @Test fun savingAPushedFormReturnsToItsItemOnce() {
        val h = history(Route.Settings("main"), list, item, form)
        h.open(item, push = false)
        assertEquals(listOf(Route.Settings("main"), list, item), h.backStack.toList())
    }
    @Test
    fun compactLayoutsSwitchSessionsInPlace() {
        val history = History(mutableStateListOf(Route.Board, Route.Chat("main", "s1")), mutableStateListOf())
        history.compact = true
        history.open(Route.Chat("main", "s2"))
        assertEquals(Route.Board, history.backStack.first())
        assertEquals(2, history.backStack.size)
        assertEquals("s2", (history.backStack.last() as Route.Chat).sessionId)
        history.compact = false
        history.open(Route.Chat("main", "s3"))
        assertEquals(3, history.backStack.size)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun sharedAgentSelectionKeepsCompactHistoryAndBackCoherent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val prefs = object : Preferences {
            private val values = mutableMapOf<String, String>()
            override fun get(key: String) = values[key]
            override fun set(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
        val credentials = object : DeviceCredentials {
            override fun read(server: String): String? = null
            override fun write(server: String, token: String) {}
            override fun delete(server: String) {}
        }
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
        }
        val access = DeviceAccess(prefs, reader)
        val owner = ConnectedServer(Notifications(emptyList()), access)
        val app = AppViewModel(Servers(prefs, credentials = credentials), Device("test"), owner, access)
        store.put("app", app)
        try {
            val a = Route.Chat("A", compose = true)
            val h = history(Route.Board, a).apply { compact = true }
            app.showView(a)
            val select: (String) -> Unit = { id ->
                app.selectAgent(id, h.backStack.last(), h.compact) { h.open(it); app.showView(it) }
            }
            select("B")
            assertEquals("B", (h.backStack.last() as Route.Chat).agentId)
            assertEquals("B", app.state.value.agentId)
            assertEquals(2, h.backStack.size)
            select("A")
            assertEquals(a, h.backStack.last())
            h.back()
            app.showView(h.backStack.last())
            assertEquals(Route.Board, h.backStack.last())
            h.forward()
            app.showView(h.backStack.last())
            assertEquals(a, h.backStack.last())
            assertEquals("A", app.state.value.agentId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

}
