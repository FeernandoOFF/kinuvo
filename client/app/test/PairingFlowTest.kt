package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModelStore
import kinuvo.protocol.device.Links
import kinuvo.protocol.device.PairLink
import kinuvo.client.TailcatForwarder
import kinuvo.client.TailcatForward
import kinuvo.client.TailcatPairing
import kinuvo.protocol.*
import kinuvo.protocol.device.*
import kinuvo.protocol.connection.ConnectParams
import kinuvo.protocol.connection.ConnectResult
import kinuvo.protocol.capability.ServerCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PairingFlowTest {
    private val unsupported = object : TailcatForwarder {
        override val supported = false
        override suspend fun pair(invite: String, device: String): TailcatPairing = error("Must not pair")
        override suspend fun open(link: String): TailcatForward = error("Must not open")
    }

    private val reader = object : DeviceReader {
        override val kinds = emptyList<String>()
        override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
        override fun read(requestJson: String, completion: (String) -> Unit) = error("Not used")
    }

    @Test fun unsupportedTailcatLinkAndPasteShowAnError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = MemoryPreferences()
        val access = DeviceAccess(prefs, reader)
        val owner = ConnectedServer(Notifications(emptyList()), access)
        val app = AppViewModel(Servers(prefs, credentials = prefs.credentials, tailcat = unsupported), Device("test"), owner, access)
        val store = ViewModelStore().also { it.put("app", app) }
        try {
            val invite = "kinuvo1:" + "a".repeat(140)
            app.openPairLink(PairLink(invite, ""))
            assertTrue(assertIs<Connection.Unpaired>(app.state.value.connection).error!!.contains("not supported"))
            app.pair(invite, "")
            advanceUntilIdle()
            assertTrue(assertIs<Connection.Unpaired>(app.state.value.connection).error!!.contains("not supported"))
            assertTrue(app.state.value.servers.isEmpty())
        } finally { store.clearAndJoin(); owner.close(); Dispatchers.resetMain() }
    }

    @Test fun independentSharingTransitionsAndErrors() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = MemoryPreferences()
        var fail = false
        var reset = false
        val sharing = object : ServerSharing {
            override var enabled = false
            override suspend fun share(enabled: Boolean) { delay(100); if (fail) error("failed"); this.enabled = enabled }
            override suspend fun reset() { delay(100); reset = true }
        }
        val embedded = object : EmbeddedServer {
            override suspend fun endpoint(): Endpoint = error("Not used")
            override val sharingOptions = mapOf(SharingOption.Tailcat to sharing)
        }
        val access = DeviceAccess(prefs, reader)
        val owner = ConnectedServer(Notifications(emptyList()), access)
        val app = AppViewModel(Servers(prefs, embedded, prefs.credentials), Device("test"), owner, access)
        val store = ViewModelStore().also { it.put("app", app) }
        try {
            app.shareTailcat(true)
            assertEquals(SharingState.Starting, app.state.value.sharingOptions[SharingOption.Tailcat])
            advanceUntilIdle()
            assertEquals(SharingState.On, app.state.value.sharingOptions[SharingOption.Tailcat])
            assertEquals(SharingState.Unavailable, app.state.value.sharingOptions[SharingOption.Lan])
            app.resetTailcat(); advanceUntilIdle(); assertTrue(reset)
            app.shareTailcat(false); advanceUntilIdle()
            assertEquals(SharingState.Off, app.state.value.sharingOptions[SharingOption.Tailcat])
            fail = true
            app.shareTailcat(true); advanceUntilIdle()
            assertEquals(SharingState.Error("failed"), app.state.value.sharingOptions[SharingOption.Tailcat])
        } finally { store.clearAndJoin(); owner.close(); Dispatchers.resetMain() }
    }

    @Test fun forgettingUsesEndpointConnectionToRevoke() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val prefs = MemoryPreferences()
        val methods = mutableListOf<String>()
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val wire = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                methods += request.method
                val payload = when (request.method) {
                    Api.Connect.name -> ProtocolJson.encodeToJsonElement(Api.Connect.result, ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.PairedDevices.name -> ProtocolJson.encodeToJsonElement(Api.PairedDevices.result, listOf(PairedDeviceInfo("device", "test", "now", current = true)))
                    else -> ProtocolJson.encodeToJsonElement(Api.RevokeDevice.result, Unit)
                }
                frames.send(Frame.Response(request.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val servers = Servers(prefs, credentials = prefs.credentials, transport = { wire })
        val server = servers.remember(Server.Remote("local:fake"), "token") as Server.Remote
        val access = DeviceAccess(prefs, reader)
        val owner = ConnectedServer(Notifications(emptyList()), access)
        val app = AppViewModel(servers, Device("test"), owner, access)
        val store = ViewModelStore().also { it.put("app", app) }
        try {
            withTimeout(5_000) { app.forget(server).join() }
            assertTrue(Api.RevokeDevice.name in methods)
            assertTrue(servers.all.isEmpty())
            assertNull(app.state.value.notice)
        } finally { store.clear(); owner.close(); Dispatchers.resetMain() }
    }

    @Test
    fun scanningATailcatInvitePairsTheTunnelThenTheDeviceWithTheCodeItCarries() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val prefs = MemoryPreferences()
        val connects = Channel<ConnectParams>(Channel.UNLIMITED)
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val wire = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val payload = if (request.method == Api.Connect.name) {
                    connects.send(ProtocolJson.decodeFromJsonElement(Api.Connect.params, request.params))
                    ProtocolJson.encodeToJsonElement(Api.Connect.result, ConnectResult("device-token", "Server", capabilities = ServerCapabilities("server"), management = false))
                } else ProtocolJson.encodeToJsonElement(Api.RevokeDevice.result, Unit)
                frames.send(Frame.Response(request.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val invites = mutableListOf<Pair<String, String>>()
        val tunnel = object : TailcatForwarder {
            override val supported = true
            override suspend fun pair(invite: String, device: String): TailcatPairing {
                invites += invite to device
                return TailcatPairing("private-link", """{"code":"654321"}""", "Kitchen")
            }
            override suspend fun open(link: String): TailcatForward = error("Not used")
        }
        val servers = Servers(prefs, credentials = prefs.credentials, transport = { wire }, tailcat = tunnel)
        val access = DeviceAccess(prefs, reader)
        val owner = ConnectedServer(Notifications(emptyList()), access)
        val app = AppViewModel(servers, Device("test"), owner, access)
        val store = ViewModelStore().also { it.put("app", app) }
        try {
            val invite = "kinuvo1:" + "a".repeat(140)
            app.openPairLink(Links.parsePair(Links.tailcatPair(invite))!!)
            assertEquals(invite, app.state.value.pendingPair?.server)
            app.pair(invite, "")
            withTimeout(5_000) { assertEquals("654321", connects.receive().pairingCode) }
            assertEquals(listOf(invite to "test"), invites)
            withTimeout(5_000) { while (app.state.value.connection !is Connection.Ready) delay(10) }
            val saved = servers.all.filterIsInstance<Server.Remote>().single()
            assertEquals(RemoteTransport.Tailcat, saved.transport)
            assertEquals("device-token", saved.token)
            assertEquals("private-link", saved.tailcatLink)
        } finally { store.clear(); owner.close(); Dispatchers.resetMain() }
    }

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
