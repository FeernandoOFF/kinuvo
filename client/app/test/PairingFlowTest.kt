package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModelStore
import kinuvo.protocol.Links
import kinuvo.protocol.PairLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PairingFlowTest {
    @Test
    fun aRememberedRemoteServerReconnectsWithoutRepeatingUnfinishedOwnerSetup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, credentials = prefs.credentials)
        val remote = servers.remember(Server.Remote("ws://home:8787/ws"), "token")
        servers.select(remote)
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Not used")
        }
        val access = DeviceAccess(prefs, reader)
        val owner = ConnectedServer(Notifications(emptyList()), access)
        val app = AppViewModel(servers, Device("test"), owner, access)
        store.put("app", app)
        try {
            assertEquals(remote, assertIs<Connection.Connecting>(app.state.value.connection).server)
            assertFalse(servers.onboardingComplete, "Setup is completed only after a successful connection")
        } finally { store.clearAndJoin(); owner.close(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test
    fun lockedCredentialStorageShowsAnErrorAndPreservesServerPreferences() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val prefs = MemoryPreferences()
        val saved = """[{"url":"ws://home:8787/ws"}]"""
        prefs["servers"] = saved
        val credentials = object : DeviceCredentials {
            override fun read(server: String): String? = error("Secure storage is locked")
            override fun write(server: String, token: String) = error("Secure storage is locked")
            override fun delete(server: String) = error("Secure storage is locked")
        }
        val notifications = Notifications(emptyList())
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Not used")
        }
        val access = DeviceAccess(prefs, reader)
        val app = AppViewModel(Servers(prefs, credentials = credentials), Device("test"), ConnectedServer(notifications, access), access)
        store.put("app", app)
        try {
            assertEquals(Route.Connect, app.startRoute)
            assertEquals("Secure storage is locked", assertIs<Connection.Unpaired>(app.state.value.connection).error)
            assertEquals(saved, prefs["servers"])
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun pairingLinkOnlyPrefillsUntilTheUserPairs() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val prefs = MemoryPreferences()
        val notifications = Notifications(emptyList())
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Not used")
        }
        val access = DeviceAccess(prefs, reader)
        val app = AppViewModel(Servers(prefs, credentials = prefs.credentials), Device("test"), ConnectedServer(notifications, access), access)
        store.put("app", app)
        try {
            val link = PairLink("https://home.ts.net", "123456")
            runCurrent()
            app.openPairLink(checkNotNull(Links.parsePair(Links.pair(link.server, link.code))))
            assertEquals(link, app.state.value.pendingPair)
            assertIs<Connection.Unpaired>(app.state.value.connection)
            assertEquals(emptyList(), app.state.value.servers)
            app.addServer()
            assertNull(app.state.value.pendingPair)
            app.openPairLink(PairLink("javascript://bad", "123456"))
            assertIs<Connection.Unpaired>(app.state.value.connection)
            assertNull(app.state.value.pendingPair)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
