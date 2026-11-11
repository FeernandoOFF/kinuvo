package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSelectionTest {
    @Test fun quickChatAndSidebarSelectTheSameRememberedSession() = withApp { app ->
        val a = Route.Chat(AgentId("A"), compose = true)
        app.showView(a)
        app.saveDraft(a, "unfinished message")
        var current: Route = a
        val open: (Route.Chat) -> Unit = { current = it; app.showView(it) }
        app.selectAgent(AgentId("B"), current, compact = true, open)
        val b = assertIs<Route.Chat>(current)
        assertEquals("B", app.state.value.agentId?.value)
        assertEquals("B", b.agentId?.value)
        assertNull(b.sessionId)
        app.selectAgent(AgentId("A"), current, compact = true, open)
        assertEquals(a, current)
        assertEquals("unfinished message", app.draft(assertIs<Route.Chat>(current)))
        app.selectAgent(AgentId("B"), current, compact = true, open)
        assertEquals(b, current)
        assertEquals("B", app.state.value.agentId?.value)
    }

    @Test fun existingSessionsAndBackDetermineSelectionWhileNonChatScreensStayOpen() = withApp { app ->
        val a = Route.Chat(AgentId("A"), SessionId("existing"))
        app.showView(a)
        var opened: Route.Chat? = null
        app.selectAgent(AgentId("B"), Route.Board, compact = true) { opened = it }
        assertNull(opened)
        assertEquals("B", app.state.value.agentId?.value)
        app.selectAgent(AgentId("A"), Route.Chat(AgentId("B")), compact = true) { opened = it }
        assertEquals(a, opened)
        app.showView(Route.Chat(AgentId("B"), SessionId("another")))
        app.showView(a)
        assertEquals("A", app.state.value.agentId?.value)
        assertEquals(a, app.lastView(AgentId("A")))
        assertEquals("existing", app.lastView().sessionId?.value)
        app.selectAgent(AgentId("A"), a, compact = true) { fail("Same Agent should keep its current Session") }
        app.selectAgent(AgentId("A"), null, compact = true) { opened = it }
        assertEquals(a, opened)
    }

    @Test fun wideLayoutsResumeTheRememberedSessionInTheSameSlot() = withApp { app ->
        val a = Route.Chat(AgentId("A"), SessionId("s1"))
        app.showView(a)
        app.showView(Route.Chat(AgentId("B"), SessionId("b1")))
        var opened: Route.Chat? = null
        app.selectAgent(AgentId("A"), Route.Chat(AgentId("B"), SessionId("b1")), compact = false) { opened = it }
        assertEquals(a, opened)
        assertEquals(a.slot, opened?.slot)
        opened = null
        app.showView(Route.Board)
        app.selectAgent(AgentId("A"), Route.Board, compact = false) { opened = it }
        assertEquals(a, opened)
        opened = null
        app.selectAgent(AgentId("A"), a, compact = false) { fail("Same Agent and Session should stay") }
        assertNull(opened)
    }

    @Test fun homeScreensListHomeAndSelectingAnAgentLeavesIt() = withApp { app ->
        app.showView(Route.Chat(AgentId("A"), SessionId("s1")))
        assertFalse(app.state.value.home)
        for (route in listOf(Route.Board, Route.Home, Route.Task("t"), Route.Knowledge(), Route.Artifacts(), Route.Computers())) {
            app.showView(Route.Chat(AgentId("A"), SessionId("s1")))
            app.showView(route)
            assertTrue(app.state.value.home, "$route")
            assertEquals("A", app.state.value.agentId?.value)
            app.selectAgent(AgentId("A"), route, compact = true) { }
            assertFalse(app.state.value.home, "$route")
        }
        app.showView(Route.Home)
        app.showView(Route.Chat(AgentId("B"), SessionId("b1")))
        assertFalse(app.state.value.home)
        app.showView(Route.Settings(AgentId("A")))
        assertFalse(app.state.value.home)
    }

    @Test fun sessionDraftsFollowTheSessionAcrossSlotsAndNewSessionDraftsStayPerSlot() = withApp { app ->
        val first = Route.Chat(AgentId("A"), SessionId("s"), slot = "one")
        val second = Route.Chat(AgentId("A"), SessionId("s"), slot = "two")
        app.saveDraft(first, "typed")
        assertEquals("typed", app.draft(second))
        assertEquals("", app.draft(Route.Chat(AgentId("A"), SessionId("other"), slot = "one")))
        assertEquals("", app.draft(Route.Chat(AgentId("B"), SessionId("s"), slot = "one")))
        app.saveDraft(second, "")
        assertEquals("", app.draft(first))

        val fresh = Route.Chat(AgentId("A"), slot = "new1")
        val other = Route.Chat(AgentId("A"), slot = "new2")
        app.saveDraft(fresh, "hello")
        assertEquals("hello", app.draft(fresh))
        assertEquals("", app.draft(other))
        val created = fresh.copy(sessionId = SessionId("made"))
        assertEquals("hello", app.draft(created))
        app.saveDraft(created, "hello again")
        assertEquals("", app.draft(fresh.copy(sessionId = null)))
        assertEquals("hello again", app.draft(created.copy(slot = "elsewhere")))
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
