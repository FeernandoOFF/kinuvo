package kinuvo.app.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.computer.ComputersViewModel
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.navigation.Route
import kinuvo.client.ConnectionState
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

/** The public Room Session and private execution Session are different identifiers. */
data class SessionContextScope(
    val agentId: String? = null,
    val sessionId: String? = null,
    val roomId: String? = null,
    val roomSessionId: String? = null,
    val memberAgentIds: List<String> = emptyList(),
) {
    fun contains(computer: ComputerInfo, selectedAgent: String?): Boolean =
        computer.control != ComputerControl.Idle && computer.agentId != null &&
            (selectedAgent == null || computer.agentId == selectedAgent) &&
            if (roomSessionId != null) computer.agentId in memberAgentIds && computer.roomSessionId == roomSessionId
            else agentId != null && sessionId != null && computer.agentId == agentId && computer.sessionId == sessionId && computer.roomSessionId == null

    fun outputs(artifacts: List<ArtifactInfo>, selectedAgent: String?): List<ArtifactInfo> = artifacts.filter {
        (selectedAgent == null || it.agentId == selectedAgent) &&
            if (roomSessionId != null) it.agentId in memberAgentIds && it.roomSessionId == roomSessionId
            else sessionId != null && it.agentId == agentId && it.sessionId == sessionId && it.roomSessionId == null
    }
}

fun SessionSettings.agentExecutionDefault() = copy(exec = null, workspace = null, container = null, ssh = null)

fun contextServerLifetime(server: ConnectedServer): Long = server.generation

fun contextualComputers(server: ConnectedServer): ComputersViewModel? = server.client?.takeIf { it.lastConnect?.management == true && it.lastConnect?.capabilities.supports(ServerFeature.Computer) }
    ?.let { ComputersViewModel(it, Route.Computers(), server, autoWatch = false) }

data class SessionContextState(
    val agentId: String? = null,
    val agents: List<AgentInfo> = emptyList(),
    val details: AgentDetails? = null,
    val routines: List<Routine>? = null,
    val runs: Map<String, List<SessionInfo>> = emptyMap(),
    val artifacts: List<ArtifactInfo>? = null,
    val artifact: ArtifactData? = null,
    val artifactId: String? = null,
    val artifactMissing: Boolean = false,
    val settings: SessionSettings = SessionSettings(),
    val capabilities: SessionCapabilities? = null,
    val running: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

class SessionContextViewModel(private val server: ConnectedServer, val context: SessionContextScope, initialAgentId: String? = context.agentId) : ViewModel() {
    val state: StateFlow<SessionContextState>
        field = MutableStateFlow(SessionContextState(agentId = initialAgentId?.takeIf { it == context.agentId || it in context.memberAgentIds }))
    val management get() = server.client?.lastConnect?.management == true
    val serverCapabilities get() = server.client?.lastConnect?.capabilities
    private var selection: Job? = null
    private var artifactLoad: Job? = null
    private var artifactOpen: Job? = null
    private var execution: Job? = null
    private var revision = 0L
    private var executionRevision = 0L
    private val mutations = kotlinx.coroutines.sync.Mutex()

    init {
        server.bind(viewModelScope)
        if (management) {
            val client = requireNotNull(server.client)
            viewModelScope.launch { server.routines.state.collect { all -> state.update { it.copy(routines = it.agentId?.let(all::get)) } } }
            viewModelScope.launch { client.events(Api.ArtifactChanged).collect { loadArtifacts() } }
            viewModelScope.launch { client.events(Api.SessionChanged).collect { if (it.agentId == state.value.agentId && it.id == context.sessionId) refreshExecution() } }
            viewModelScope.launch { client.state.collect { if (it is ConnectionState.Connected) { selectAgent(state.value.agentId); loadArtifacts() } } }
            attempt(memberScoped = false) { val agents = server.config.agents(); state.update { it.copy(agents = agents) } }
            if (context.roomId != null && context.roomSessionId != null) viewModelScope.launch {
                while (isActive) { refreshExecution().join(); delay(1000) }
            }
        }
    }

    fun selectAgent(id: String?) {
        if (id != null && id != context.agentId && id !in context.memberAgentIds) return
        revision++
        executionRevision++
        val generation = revision
        selection?.cancel()
        execution?.cancel()
        state.update { it.copy(agentId = id, details = null, routines = id?.let { server.routines.state.value[it] }, settings = SessionSettings(), capabilities = null, runs = emptyMap(), error = null, saving = false) }
        id ?: return
        selection = attempt {
            val details = server.config.details(id)
            if (revision != generation) return@attempt
            state.update { it.copy(details = details) }
            server.routines.load(id).join()
            if (revision != generation) return@attempt
            val routines = server.routines.state.value[id] ?: error("Couldn't load Routines. Reopen this panel to try again.")
            state.update { it.copy(routines = routines) }
            refreshExecution().join()
        }
    }

    fun refreshExecution(): Job {
        val agent = state.value.agentId
        val generation = revision
        if (agent == null || state.value.saving) return viewModelScope.launch {}
        val request = ++executionRevision
        execution?.cancel()
        return attempt { loadExecution(agent, generation, request) }.also { execution = it }
    }

    private suspend fun loadExecution(agent: String, generation: Long, request: Long) {
        val room = context.roomId
        val public = context.roomSessionId
        if (room != null && public != null) {
            val view = server.rooms.view(room, public)
            if (revision == generation && executionRevision == request) state.update { it.copy(
                settings = view.session.memberSettings[agent] ?: SessionSettings(),
                capabilities = view.members[agent]?.let { c -> c.copy(active = view.progress?.takeIf { it.agentId == agent && view.running }?.effective) },
                running = view.progress?.agentId == agent && view.running) }
        } else {
            val id = context.sessionId
            val info = id?.let { server.call { it.call(Api.Session, SessionParams(agent, id)) } }
            if (revision != generation || executionRevision != request) return
            val capabilities = server.call { it.call(Api.Capabilities, CapabilitiesParams(agent, id)) }
            if (revision == generation && executionRevision == request) state.update { it.copy(settings = info?.settings ?: SessionSettings(), capabilities = capabilities, running = info?.status in setOf(SessionStatus.Running, SessionStatus.Waiting)) }
        }
    }

    fun saveExecution(settings: SessionSettings): Job {
        val agent = state.value.agentId ?: return viewModelScope.launch {}
        val generation = revision
        val defaults = state.value.details?.settings
        return attempt {
            mutations.withLock {
                if (revision != generation) return@withLock
                validateSessionExecution(settings, requireNotNull(defaults) { "Load Agent execution settings first" })
                val request = ++executionRevision
                execution?.cancel()
                state.update { it.copy(saving = true, error = null) }
                try {
                    if (context.roomId != null && context.roomSessionId != null) server.call { it.call(Api.ConfigureRoomSession, ConfigureRoomSessionParams(context.roomId, context.roomSessionId, agent, settings)) }
                    else server.call { it.call(Api.ConfigureSession, ConfigureSessionParams(agent, requireNotNull(context.sessionId) { "Start a Session to choose its execution" }, settings)) }
                    if (revision == generation) {
                        state.update { it.copy(settings = settings) }
                        loadExecution(agent, generation, request)
                    }
                } finally { if (revision == generation) state.update { it.copy(saving = false) } }
            }
        }
    }

    fun updateRoutine(id: String, spec: RoutineSpec): Job {
        val agent = state.value.agentId ?: return viewModelScope.launch {}
        return attempt {
            server.routines.update(agent, id, spec)
            server.routines.load(agent)
        }
    }

    fun loadRuns(id: String): Job {
        val agent = state.value.agentId ?: return viewModelScope.launch {}
        val generation = revision
        return attempt {
            val runs = server.routines.runs(agent, id)
            if (revision == generation) state.update { it.copy(runs = it.runs + (id to runs)) }
        }
    }

    fun openArtifact(id: String?) {
        artifactOpen?.cancel()
        state.update { it.copy(artifactId = id, artifact = null, artifactMissing = false) }
        id ?: return
        artifactOpen = attempt(memberScoped = false) {
            val data = server.call { it.call(Api.Artifact, ArtifactRef(id)) }
            if (state.value.artifactId == id) state.update { it.copy(artifact = data) }
        }
    }

    private fun loadArtifacts() {
        artifactLoad?.cancel()
        artifactLoad = attempt(memberScoped = false) {
            val all = server.call { it.call(Api.Artifacts, Unit) }
            state.update { it.copy(artifacts = all, artifactMissing = it.artifactId != null && all.none { a -> a.id == it.artifactId }) }
            state.value.artifactId?.takeIf { id -> all.any { it.id == id } }?.let(::openArtifact)
        }
    }

    fun dispose() { viewModelScope.cancel() }

    private fun attempt(memberScoped: Boolean = true, block: suspend () -> Unit): Job {
        val generation = revision
        return viewModelScope.launch {
            try { server.run { if (!memberScoped || generation == revision) block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == revision || !memberScoped) state.update { if (!memberScoped && it.error != null) it else it.copy(error = e.message ?: e.toString()) } }
        }
    }
}


fun validateSessionExecution(settings: SessionSettings, defaults: AgentSettings) {
    // An empty execution override follows a valid Agent configuration without pinning it.
    if (settings.exec == null && settings.workspace == null && settings.container == null && settings.ssh == null) return
    val backend = settings.exec ?: defaults.exec
    require(backend in setOf("none", "host", "container", "ssh")) { "Choose an Exec backend" }
    if (backend != "none") require((settings.workspace ?: defaults.workspace).any { it.isNotBlank() }) { "Add at least one Workspace folder" }
    if (backend == "container") require((settings.container ?: defaults.container).image.isNotBlank()) { "Enter a container image" }
    if (backend == "ssh") {
        val ssh = settings.ssh ?: defaults.ssh
        require(!ssh?.host.isNullOrBlank()) { "Configure an SSH host in Agent settings first" }
        val port = requireNotNull(ssh).port
        require(port == null || port in 1..65535) { "SSH port must be between 1 and 65535" }
    }
}
