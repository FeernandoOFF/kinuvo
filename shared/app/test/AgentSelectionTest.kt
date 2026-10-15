package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSelectionTest {
    @Test fun quickChatAndSidebarSelectTheSameRememberedSession() = withApp { app ->
        val a = Route.Chat("A", compose = true)
        app.showView(a)
        app.saveDraft(a.slot, "unfinished message")
        var current: Route = a
        val open: (Route.Chat) -> Unit = { current = it; app.showView(it) }
        app.selectAgent("B", current, compact = true, open)
        val b = assertIs<Route.Chat>(current)
        assertEquals("B", app.state.value.agentId)
        assertEquals("B", b.agentId)
        assertNull(b.sessionId)
        app.selectAgent("A", current, compact = true, open)
        assertEquals(a, current)
        assertEquals("unfinished message", app.draft(assertIs<Route.Chat>(current).slot))
        app.selectAgent("B", current, compact = true, open)
        assertEquals(b, current)
        assertEquals("B", app.state.value.agentId)
    }

    @Test fun existingSessionsAndBackDetermineSelectionWhileNonChatScreensStayOpen() = withApp { app ->
        val a = Route.Chat("A", "existing")
        app.showView(a)
        var opened: Route.Chat? = null
        app.selectAgent("B", Route.Board, compact = true) { opened = it }
        assertNull(opened)
        assertEquals("B", app.state.value.agentId)
        app.selectAgent("A", Route.Chat("B"), compact = true) { opened = it }
        assertEquals(a, opened)
        app.showView(Route.Chat("B", "another"))
        app.showView(a)
        assertEquals("A", app.state.value.agentId)
        assertEquals(a, app.lastView("A"))
        assertEquals("existing", app.lastView().sessionId)
        app.selectAgent("A", a, compact = true) { fail("Same Agent should keep its current Session") }
        app.selectAgent("A", null, compact = true) { opened = it }
        assertEquals(a, opened)
    }

    private fun withApp(block: (AppViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val prefs = MemoryPreferences()
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Unused")
        }
        val access = DeviceAccess(prefs, reader)
        val owner = ConnectedServer(Notifications(emptyList()), access)
        val app = AppViewModel(Servers(prefs, credentials = prefs.credentials), Device("test"), owner, access)
        store.put("app", app)
        try { block(app) } finally { store.clearAndJoin(); owner.close(); runCurrent(); Dispatchers.resetMain() }
    }
}
