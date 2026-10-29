package kinuvo.app.connection

import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.routine.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.client.AuthException
import kinuvo.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private val ConnectTimeout = 15.seconds

sealed interface Connection {
    data class Connecting(val server: Server? = null) : Connection
    data class Ready(val client: KinuvoClient, val server: Server) : Connection
    data class Unpaired(val error: String? = null, val server: Server.Remote? = null) : Connection
}

/**
 * Everything app-wide the UI shows. [agentId] is the Agent the shell shows; [sessions] mirrors the [SessionRepository]:
 * session lists and finished marks; [pairing] is set when the connected Server can pair other Devices; [capabilities]
 * are the connected Server's features; [routines] mirrors the [RoutineRepository] for the Agents shown. With [home], the
 * shell's column lists Home's destinations instead of [agentId]'s Sessions.
 */
data class AppState(
    val connection: Connection = Connection.Connecting(),
    val servers: List<Server> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    val agentId: String? = null,
    val home: Boolean = false,
    val sessions: RepositoryState = RepositoryState(),
    val usage: Usage? = null,
    val pairing: PairingInfo? = null,
    val pendingPair: PairLink? = null,
    val pairingBase: String? = null,
    val pairingError: String? = null,
    val devices: DevicesState = DevicesState(),
    val notice: String? = null,
    val capabilities: ServerCapabilities? = null,
    val routines: Map<String, List<Routine>> = emptyMap(),
    val onboarding: Boolean = false,
    /** The Server to go back to when adding another one is cancelled. */
    val returnTo: Server? = null,
    /** Whether the embedded Server is reachable from other Devices; null when it can't be. */
    val sharing: Boolean? = null,
)

/** App-scoped: owns the Server connection so it outlives screens and configuration changes. */
class AppViewModel(
    private val servers: Servers,
    private val device: Device,
    private val owner: ConnectedServer,
    private val deviceAccess: DeviceAccess,
) : ViewModel() {
    private val repo get() = owner.sessions
    private val routines get() = owner.routines
    private val devices get() = owner.devices
    val serverLifetime get() = owner.generation
    private val initialServers = runCatching { servers.all to servers.current }
    val state: StateFlow<AppState>
        field = MutableStateFlow(AppState(servers = initialServers.getOrNull()?.first.orEmpty(), agentId = servers.lastView?.agentId, onboarding = !servers.onboardingComplete))

    /** Where the app opens: the last view when there's a Server to reconnect to. */
    val startRoute: Route = if (initialServers.getOrNull()?.second != null) lastView() else Route.Connect

    fun lastView(): Route.Chat = Route.chat(servers.lastView)

    private val connected get() = state.value.connection as? Connection.Ready
    private val currentServer get() = when (val connection = state.value.connection) {
        is Connection.Ready -> connection.server
        is Connection.Connecting -> connection.server
        is Connection.Unpaired -> connection.server
    }
    private var connecting: Job? = null
    private val views = servers.lastView?.let { mutableMapOf(it.agentId to Route.chat(it)) } ?: mutableMapOf()
    private val drafts = mutableMapOf<String, String>()
    private var selecting: Job? = null
    private var selection = 0L
    private var watching: Job? = null

    fun finishOnboarding() {
        servers.onboardingComplete = true
        state.update { it.copy(onboarding = false) }
    }

    init {
        if (state.value.onboarding) servers.onboardingComplete = false
        initialServers.getOrNull()?.second?.takeUnless { state.value.onboarding && it !is Server.Remote }?.let(::use)
            ?: state.update { it.copy(connection = Connection.Unpaired(initialServers.exceptionOrNull()?.message)) }
        viewModelScope.launch { repo.state.collect { r -> state.update { it.copy(sessions = r) } } }
        viewModelScope.launch { routines.state.collect { r -> state.update { it.copy(routines = r) } } }
        viewModelScope.launch { devices.state.collect { d -> state.update { it.copy(devices = d) } } }
        viewModelScope.launch { pendingPairLink.collect { link ->
            if (link != null) { pendingPairLink.value = null; openPairLink(link) }
        } }
    }

    fun pair(url: String, code: String, name: String = "") {
        val normalized = try { normalizeUrl(url) } catch (e: IllegalArgumentException) {
            state.update { it.copy(connection = Connection.Unpaired(e.message)) }; return
        }
        connect(Server.Remote(normalized, name = name.trim().ifEmpty { null }), code.trim())
    }

    fun openPairLink(link: PairLink) {
        val url = runCatching { normalizeUrl(link.server) }.getOrNull() ?: return
        val known = runCatching { servers.all }.getOrDefault(state.value.servers).filterIsInstance<Server.Remote>().firstOrNull { it.url == url && it.token != null }
        if (known != null) {
            state.update { it.copy(pendingPair = link) }
            use(known)
            return
        }
        disconnect()
        state.update { it.copy(connection = Connection.Unpaired(), pendingPair = link) }
    }

    fun rename(server: Server.Remote, name: String) {
        val renamed = servers.rename(server, name)
        val ready = connected
        state.update { it.copy(servers = servers.all, connection = if (ready?.server?.key == server.key) ready.copy(server = renamed) else it.connection) }
    }

    fun selectPairingBase(base: String) {
        if (base !in state.value.pairing?.bases.orEmpty()) return
        state.value.capabilities?.serverId?.let { servers.setPairingBase(it, base) }
        state.update { it.copy(pairingBase = base) }
    }

    fun dismissNotice() { state.update { it.copy(notice = null) } }

    fun forget(server: Server.Remote) = viewModelScope.launch {
        val ready = connected
        var temporary: KinuvoClient? = null
        val revoked = server.token == null || withTimeoutOrNull(3.seconds) {
            try {
                val client = ready?.client?.takeIf { ready.server.key == server.key } ?: KinuvoClient(server.url, device.name, token = server.token).also { temporary = it; it.connect() }
                client.call(Api.PairedDevices, Unit).firstOrNull { it.current }?.let { client.call(Api.RevokeDevice, DeviceRef(it.id)) }
                true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            finally { temporary?.close() }
        } == true
        val leaving = currentServer?.key == server.key
        if (leaving) disconnect()
        servers.forget(server)
        state.update { it.copy(servers = servers.all, notice = if (revoked) null else "Forgotten here; still paired on the server") }
        if (leaving) {
            servers.all.firstOrNull { it is Server.Embedded }?.let(::use)
                ?: state.update { it.copy(connection = Connection.Unpaired()) }
        }
    }

    fun revokeDevice(target: PairedDeviceInfo) {
        val ready = connected ?: return
        viewModelScope.launch {
            try {
                connectedCall(ready) { it.call(Api.RevokeDevice, DeviceRef(target.id)) }
                if (target.current && ready.server is Server.Remote) {
                    val saved = servers.all.filterIsInstance<Server.Remote>().firstOrNull { it.key == ready.server.key }
                    if (saved != null && (saved.token == ready.server.token || saved.token == null)) {
                        val unpaired = servers.unpair(saved)
                        if (currentServer?.key == saved.key) {
                            disconnect()
                            state.update { it.copy(connection = Connection.Unpaired("Unpaired by the server", unpaired), servers = servers.all, pendingPair = null) }
                        }
                        state.update { it.copy(servers = servers.all) }
                    }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); state.update { it.copy(notice = e.message ?: "Couldn't revoke Device") } }
        }
    }

    fun rotateToken() {
        val ready = connected ?: return
        viewModelScope.launch {
            try {
                connectedCall(ready) { it.rotateToken() }
                val result = requireNotNull(ready.client.lastConnect)
                val saved = servers.remember(ready.server, result.token, result.capabilities.serverId, result.serverName)
                state.update { it.copy(connection = if (connected?.client === ready.client) Connection.Ready(ready.client, saved) else it.connection,
                    servers = servers.all, notice = "Device credential rotated") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); state.update { it.copy(notice = e.message ?: "Couldn't rotate Device credential") } }
        }
    }

    private suspend fun <T> connectedCall(ready: Connection.Ready, block: suspend (KinuvoClient) -> T): T = owner.call {
        if (it !== ready.client) throw CancellationException("Server changed")
        block(it)
    }

    fun use(server: Server) = connect(server)

    /** Opens or closes the embedded Server to other Devices, then reconnects so pairing shows or hides. */
    fun share(enabled: Boolean) {
        val sharing = servers.sharing ?: return
        viewModelScope.launch {
            try { sharing.share(enabled); connect(Server.Embedded) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(notice = e.message ?: "Couldn't change sharing") } }
        }
    }

    fun addServer() {
        val current = connected?.server
        disconnect()
        state.update { it.copy(connection = Connection.Unpaired(), pendingPair = null, returnTo = current) }
    }

    fun cancelAddServer() {
        state.value.returnTo?.let(::use)
    }

    /** Re-reads the pairing details, e.g. because the code rotated. */
    fun refreshPairing(regenerate: Boolean = false) = requestPairing(regenerate, false)

    fun managementPairing() = requestPairing(true, true)

    private fun requestPairing(regenerate: Boolean, management: Boolean) {
        val ready = connected ?: return
        viewModelScope.launch {
            try {
                val endpoint = servers.endpoint(ready.server)
                val info = pairingInfo(endpoint.url, connectedCall(ready) { it.call(Api.Pairing, PairingParams(regenerate, management)) })
                if (connected?.client !== ready.client) return@launch
                val selected = state.value.pairingBase ?: state.value.capabilities?.serverId?.let(servers::pairingBase)
                state.update { it.copy(pairing = info, pairingBase = selected?.takeIf { it in info.bases } ?: info.bases.firstOrNull(), pairingError = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); if (connected?.client === ready.client) state.update { it.copy(pairingError = e.message) } }
        }
    }

    /** [route] with the Agent the shell shows when it has none, e.g. a shortcut's new Session. */
    fun withShownAgent(route: Route.Chat) =
        if (route.agentId != null) route else route.copy(agentId = state.value.agentId ?: state.value.agents.firstOrNull()?.id)

    /** The Session last shown for [agentId], or a new one. */
    fun lastView(agentId: String) = views[agentId] ?: Route.Chat(agentId)

    /** A Session's unsent text, kept per Server and Session, or per screen until a new Session has an ID. */
    fun draft(route: Route.Chat): String = drafts[draftKey(route)] ?: drafts[route.slot].orEmpty()
    fun saveDraft(route: Route.Chat, text: String) {
        drafts.remove(route.slot)
        if (text.isEmpty()) drafts.remove(draftKey(route)) else drafts[draftKey(route)] = text
    }

    private fun draftKey(route: Route.Chat) = route.sessionId?.let { "${currentServer?.key}/${route.agentId}/$it" } ?: route.slot

    /** Called only by the navigation host for its active destination, including Back. */
    fun showView(route: Route) {
        selecting?.cancel()
        selection++
        if (route is Route.Chat) route.agentId?.let { id ->
            servers.lastView = LastView(id, route.sessionId)
            views[id] = route
            showAgent(id)
        }
        if (route.home) showHome()
    }

    /** Lists Home's destinations in the shell's column; the shown Agent stays selected for its return. */
    fun showHome() = state.update { it.copy(home = true) }

    /** An unresolved active screen must not replace a newer Agent selection. */
    fun resolveView(requested: Route.Chat, resolved: Route.Chat): Route.Chat? = resolved.takeIf {
        requested.agentId == state.value.agentId || requested.agentId == null && selecting?.isActive != true
    }

    /**
     * Selects the shell Agent and decides which Session the host should show: the one it last showed for [agentId], or
     * its latest. On [compact] layouts other screens stay open until a Session is picked from the column.
     */
    fun selectAgent(agentId: String, current: Route?, compact: Boolean, onOpen: (Route.Chat) -> Unit) {
        val previous = state.value.agentId
        selecting?.cancel()
        val request = ++selection
        showAgent(agentId)
        if (compact) {
            if (current == null || current is Route.Chat && current.agentId != agentId) onOpen(lastView(agentId))
        } else if (agentId != previous || current !is Route.Chat || current.agentId != agentId) {
            views[agentId]?.let { return onOpen(it) }
            val lifetime = serverLifetime
            selecting = viewModelScope.launch {
                val session = latestSession(agentId)
                if (selection == request && serverLifetime == lifetime) onOpen(Route.Chat(agentId, session))
            }
        }
    }

    /** Shows [agentId] in the shell, loading its Sessions' first page and Routines once. */
    fun showAgent(agentId: String) {
        state.update { it.copy(agentId = agentId, home = false) }
        if (agentId !in state.value.sessions.lists) repo.loadSessions(agentId)
        if (agentId !in state.value.routines) routines.load(agentId)
    }

    fun loadMoreSessions(agentId: String) = repo.loadSessions(agentId, more = true)

    /** Starts a Session with [agentId] by sending [text]; returns its ID, or null when it couldn't. */
    suspend fun quickSend(agentId: String, text: String): String? =
        catching { owner.run { repo.createSession(agentId).id.also { repo.send(agentId, it, text) } } }.getOrNull()

    private suspend fun latestSession(agentId: String): String? =
        catching { owner.call { it.call(Api.Sessions, SessionsParams(agentId, limit = 1)).sessions.firstOrNull()?.id } }.getOrNull()

    private fun connect(server: Server, code: String? = null) {
        disconnect()
        state.update { it.copy(connection = Connection.Connecting(server)) }
        connecting = viewModelScope.launch { open(server, code) }
    }

    private suspend fun open(server: Server, code: String?) {
        var active = server
        var client: KinuvoClient? = null
        try {
            val endpoint = servers.endpoint(server)
            currentCoroutineContext().ensureActive()
            client = KinuvoClient(endpoint.url, device.name, token = endpoint.token, pairingCode = code, timeZone = device.timeZone,
                deviceTools = { deviceAccess.tools(endpoint.url) }, deviceRead = { deviceAccess.read(endpoint.url, it) },
                computer = { deviceAccess.act(endpoint.url, it) }, connection = endpoint.connection)
            owner.open(client, endpoint.url)
            withTimeoutOrNull(ConnectTimeout) { owner.run { client.connect() } } ?: error("Timed out connecting to ${server.label}")
            client.lastConnect?.let { result -> active = servers.remember(active, result.token, result.capabilities.serverId, result.serverName) }
            servers.select(active)
        } catch (e: Exception) {
            client?.close()
            currentCoroutineContext().ensureActive()
            if (e is CancellationException) throw e
            owner.close()
            if (e is AuthException && server is Server.Remote && server.token != null) runCatching { active = servers.unpair(server) }
            val saved = runCatching { servers.all }.getOrDefault(state.value.servers)
            state.update { it.copy(connection = Connection.Unpaired(e.message ?: e.toString(), active as? Server.Remote), servers = saved) }
            return
        }
        state.update {
            it.copy(connection = Connection.Ready(client, active), servers = servers.all, usage = client.lastConnect?.usage, pendingPair = null, returnTo = null,
                capabilities = client.lastConnect?.capabilities, sharing = servers.sharing?.enabled)
        }

        owner.activate()
        if (state.value.onboarding && (server is Server.Remote || client.lastConnect?.management == false)) finishOnboarding()
        watching = owner.scope.launch {
            launch { client.events(Api.Usage).collect { u -> state.update { it.copy(usage = u) } } }
            launch { client.events(Api.AgentsChanged).collect(::setAgents) }
            launch { client.events(Api.DevicesChanged).collect { state.update { it.copy(pairing = null) } } }
            client.state.collect {
                if (it is ConnectionState.Disconnected && it.rejected) {
                    val remote = connected?.server as? Server.Remote ?: return@collect
                    val unpaired = servers.unpair(remote)
                    disconnect()
                    state.update { s -> s.copy(connection = Connection.Unpaired("Unpaired by the server", unpaired), servers = servers.all, pendingPair = null) }
                    return@collect
                }
                if (it !is ConnectionState.Connected) return@collect
                client.lastConnect?.let { result ->
                    val current = connected?.server ?: return@let
                    val remembered = servers.remember(current, result.token, result.capabilities.serverId, result.serverName)
                    state.update { s -> s.copy(connection = Connection.Ready(client, remembered), servers = servers.all, capabilities = result.capabilities) }
                }
                refreshAgents()
            }
        }
    }

    private fun disconnect() {
        selecting?.cancel()
        selection++
        connecting?.cancel()
        watching?.cancel()
        owner.close()
        state.update { it.copy(agents = emptyList(), sessions = RepositoryState(), routines = emptyMap(), devices = DevicesState(), usage = null, pairing = null, pairingBase = null, pairingError = null, capabilities = null) }
    }

    private suspend fun refreshAgents() {
        if (connected == null) return
        catching { owner.call { it.call(Api.Agents, Unit) } }.onSuccess(::setAgents)
    }

    /** Loads every Agent's first page of Sessions too, so the rail can mark the ones with news. */
    private fun setAgents(agents: List<AgentInfo>) {
        state.update { it.copy(agents = agents) }
        agents.filter { it.id !in state.value.sessions.lists }.forEach { repo.loadSessions(it.id) }
        // Home lists every Agent's next and latest runs.
        agents.filter { it.id !in state.value.routines }.forEach { routines.load(it.id) }
    }

    override fun onCleared() {
        disconnect()
    }
}
