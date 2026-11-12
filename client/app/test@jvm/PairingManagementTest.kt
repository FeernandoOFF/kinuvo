package kinuvo.app

import kinuvo.app.agent.*
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.Route

import androidx.lifecycle.ViewModelStore
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.device.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PairingManagementTest {
    @Test
    fun pairingLinkSurvivesTryingAnExpiredSavedBrowserSession() = runBlocking {
        withTimeout(5_000) {
            val port = java.net.ServerSocket(0).use { it.localPort }
            withApp { app, servers, owner ->
                val url = "ws://127.0.0.1:$port/ws"
                servers.remember(Server.Remote(url), "browser-session")
                val link = PairLink("http://127.0.0.1:$port", "a".repeat(64))
                app.openPairLink(link)
                assertEquals(link, app.state.value.pendingPair)
                app.state.first { it.connection is Connection.Unpaired }
                assertEquals(link, app.state.value.pendingPair)
            }
        }
    }
    @Test
    fun ordinaryDevicesSkipOwnerSetupWhileManagementDevicesKeepIt() = runBlocking {
        withTimeout(10_000) {
            for (management in listOf(false, true)) {
                val connection = DeviceConnection(management = management)
                val embedded = object : EmbeddedServer {
                    override suspend fun endpoint() = Endpoint("local:test", "token", { connection })
                }
                withApp(embedded) { app, servers, owner ->
                    assertTrue(app.state.value.onboarding)
                    assertIs<Connection.Unpaired>(app.state.value.connection)
                    app.use(Server.Embedded)
                    app.state.first { it.connection is Connection.Ready && it.onboarding == management }
                    assertEquals(!management, servers.onboardingComplete)
                }
            }
        }
    }

    @Test
    fun forgettingDoesNotReplaceANewerReadyOrConnectingServer() = runBlocking {
        withTimeout(10_000) {
            for (connecting in listOf(false, true)) {
                val release = CompletableDeferred<Unit>()
                val connection = DeviceConnection(release)
                val client = KinuvoClient("local:test", "test", connection = { connection })
                client.connect()
                withApp { app, servers, owner ->
                    val first = servers.remember(Server.Remote("ws://first:8787/ws", name = "Office"), "first-token") as Server.Remote
                    val second = servers.remember(Server.Remote("ws://second:8787/ws"), "second-token")
                    servers.select(first)
                    val state = app.state as MutableStateFlow<AppState>
                    state.value = state.value.copy(connection = Connection.Ready(client, first), servers = servers.all)
                    val forgotten = app.forget(first)
                    connection.listed.await()
                    val selected = if (connecting) Connection.Connecting(second) else Connection.Ready(client, second)
                    state.value = state.value.copy(connection = selected)
                    servers.select(second)
                    release.complete(Unit)
                    forgotten.join()
                    assertEquals(selected, app.state.value.connection)
                    assertEquals(listOf(second), app.state.value.servers)
                    assertEquals(second, servers.current)
                    assertTrue(connection.revoked)
                }
                client.close()
            }
        }
    }

    @Test
    fun forgettingTheConnectingServerCancelsItAndShowsConnect() = runBlocking {
        withApp { app, servers, owner ->
            val server = servers.remember(Server.Remote("ws://home:8787/ws"), "token") as Server.Remote
            val state = app.state as MutableStateFlow<AppState>
            state.value = state.value.copy(connection = Connection.Connecting(server), servers = servers.all)
            app.forget(server.copy(token = null)).join()
            assertIs<Connection.Unpaired>(app.state.value.connection)
            assertEquals(emptyList(), app.state.value.servers)
        }
    }

    @Test
    fun revokingThisDeviceKeepsItsServerAddressAndAlias() = runBlocking {
        withTimeout(10_000) {
            val connection = DeviceConnection()
            val client = KinuvoClient("local:test", "test", connection = { connection })
            client.connect()
            withApp { app, servers, owner ->
                val server = servers.remember(Server.Remote("ws://home:8787/ws", name = "Office"), "token", "home") as Server.Remote
                servers.select(server)
                val state = app.state as MutableStateFlow<AppState>
                state.value = state.value.copy(connection = Connection.Ready(client, server), servers = servers.all)
                owner.open(client, "local:test")
                app.revokeDevice(connection.device)
                app.state.first { it.connection is Connection.Unpaired }
                val unpaired = server.copy(token = null)
                assertEquals(listOf(unpaired), app.state.value.servers)
                assertEquals(unpaired, servers.current)
                assertEquals(unpaired, (app.state.value.connection as Connection.Unpaired).server)
                assertTrue(connection.revoked)
            }
            client.close()
        }
    }

    @Test
    fun aNotificationsLinkSwitchesToTheServerThatSentIt() = runBlocking {
        withTimeout(5_000) {
            withApp { app, servers, _ ->
                val home = servers.remember(Server.Remote("ws://127.0.0.1:1/ws"), "home-token", "home-id")
                val office = servers.remember(Server.Remote("ws://127.0.0.1:2/ws"), "office-token", "office-id")
                fun selected() = when (val c = app.state.value.connection) {
                    is Connection.Ready -> c.server
                    is Connection.Connecting -> c.server
                    is Connection.Unpaired -> c.server
                }
                app.use(home)
                pendingLink.value = null
                openLink(Links.chat(AgentId("kai"), SessionId("s1"), "office-id"))
                pendingLink.first { it?.agentId?.value == "kai" && it.sessionId?.value == "s1" }
                assertEquals(office.key, selected()?.key)
                assertEquals(AgentId("kai") to SessionId("s1"), pendingLink.value?.let { it.agentId to it.sessionId })
                app.openChat(ChatLink(AgentId("kai"), SessionId("s2"), "unknown"))
                assertEquals(office.key, selected()?.key)
                assertEquals("s2", pendingLink.value?.sessionId?.value)
                pendingLink.value = null
            }
        }
    }

    @Test
    fun opensAChatBeforeAskingForNotifications() = runBlocking {
        var asked = 0
        withApp(permission = { asked++ }) { app, _, _ ->
            app.showView(Route.Chat(AgentId("kai")))
            assertEquals(0, asked)
            app.finishOnboarding()
            app.showView(Route.Board)
            assertEquals(0, asked)
            app.showView(Route.Chat(AgentId("kai")))
            assertEquals(1, asked)
        }
    }

    private suspend fun withApp(embedded: EmbeddedServer? = null, permission: NotificationPermission = NotificationPermission.None, block: suspend (AppViewModel, Servers, ConnectedServer) -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val store = ViewModelStore()
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, embedded, prefs.credentials)
        val notifications = Notifications(emptyList())
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Not used")
        }
        val access = DeviceAccess(prefs, reader)
        val owner = ConnectedServer(notifications, access)
        val app = AppViewModel(servers, Device("test"), owner, access, notificationPermission = permission)
        store.put("app", app)
        try { block(app, servers, owner) }
        finally {
            val serverJob = owner.scope.coroutineContext.job
            store.clearAndJoin()
            owner.close()
            serverJob.join()
            Dispatchers.resetMain()
        }
    }

    private class DeviceConnection(private val releaseList: CompletableDeferred<Unit>? = null, private val management: Boolean = true) : FrameConnection {
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val listed = CompletableDeferred<Unit>()
        val device = PairedDeviceInfo("test", "test", "2026-10-10T04:00:00Z", current = true)
        var revoked = false

        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            when (request.method) {
                Api.Connect.name -> respond(request, Api.Connect, ConnectResult("token", "test", capabilities = ServerCapabilities("test"), management = management))
                Api.Presence.name -> respond(request, Api.Presence, Unit)
                Api.PairedDevices.name -> {
                    listed.complete(Unit)
                    releaseList?.await()
                    respond(request, Api.PairedDevices, listOf(device))
                }
                Api.RevokeDevice.name -> {
                    assertEquals(device.id, ProtocolJson.decodeFromJsonElement(Api.RevokeDevice.params, request.params).id)
                    revoked = true
                    respond(request, Api.RevokeDevice, Unit)
                }
                else -> frames.send(Frame.Response(request.id, false, error = WireError(WireErrorCode.Internal, "Unavailable in this test")))
            }
        }

        private suspend fun <P, R> respond(request: Frame.Request, method: Method<P, R>, result: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, result)))
        }

        override suspend fun close() { frames.close() }
    }
}
