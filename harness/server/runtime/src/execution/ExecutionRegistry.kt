package kinuvo.harness.server.execution

import kinuvo.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Configuration is distinct from live capability negotiation. No executable is launched by CRUD. */
class ExecutionRegistry(initial: ExecutionCatalog = ExecutionCatalog(emptyList(), emptyList()), private val persist: (ExecutionCatalog) -> Unit = {}) {
    private val lock = Mutex()
    private val connections = linkedMapOf<String, HarnessConnectionStatus>()
    private val agents = linkedMapOf<String, AgentRegistration>()

    init {
        initial.connections.forEach { connections[it.config.id] = it.copy(readiness = ExecutionReadiness(true, false, "Not connected")) }
        initial.agents.forEach { agents[it.id] = it }
    }
    private fun save() = persist(ExecutionCatalog(connections.values.toList(), agents.values.toList()))
    suspend fun registration(id: String) = lock.withLock { agents[id] }
    suspend fun configured(agentId: String) = lock.withLock { connections.getValue(agents.getValue(agentId).connectionId).config }

    suspend fun catalog() = lock.withLock { ExecutionCatalog(connections.values.toList(), agents.values.toList()) }

    suspend fun save(config: HarnessConnection): HarnessConnectionStatus = lock.withLock {
        require(config.id.isNotBlank() && config.name.isNotBlank())
        when (val c = config.connection) {
            is ExecutionConnection.Acp -> require(c.command.isNotEmpty() && c.command.all { it.isNotBlank() })
            is ExecutionConnection.Remote -> require(c.serverId.isNotBlank())
        }
        HarnessConnectionStatus(config, ExecutionReadiness(true, false, "Connection has not been negotiated")).also { connections[config.id] = it; save() }
    }

    suspend fun register(agent: AgentRegistration): AgentRegistration = lock.withLock {
        require(agent.id.isNotBlank() && agent.name.isNotBlank())
        require(agent.connectionId in connections) { "Unknown execution connection" }
        when (connections.getValue(agent.connectionId).config.connection) {
            is ExecutionConnection.Acp -> require(agent.cwd?.let { absoluteExecutionPath(it) } == true) { "Working folder must be absolute" }
            is ExecutionConnection.Remote -> require(!agent.remoteAgentId.isNullOrBlank()) { "Select a remote Agent" }
        }
        agent.also { agents[it.id] = it; save() }
    }

    suspend fun removeAgent(id: String) = lock.withLock { agents.remove(id); save(); Unit }

    suspend fun removeConnection(id: String) = lock.withLock {
        require(agents.values.none { it.connectionId == id }) { "Connection is used by an Agent" }
        connections.remove(id)
        save()
        Unit
    }

    suspend fun negotiated(id: String, capabilities: ExecutionCapabilities) = lock.withLock {
        val status = requireNotNull(connections[id])
        connections[id] = status.copy(readiness = ExecutionReadiness(true, true), capabilities = capabilities)
    }

    suspend fun offline(id: String, reason: String, reasonCode: String = "host_offline") = lock.withLock {
        val status = requireNotNull(connections[id])
        connections[id] = status.copy(readiness = ExecutionReadiness(true, false, reason, reasonCode))
    }

    /** Lookup never falls back to another Server or Agent. */
    suspend fun route(agentId: String): HarnessConnection = lock.withLock {
        val agent = requireNotNull(agents[agentId]) { "Unknown external Agent" }
        val status = requireNotNull(connections[agent.connectionId])
        check(status.readiness.online) { status.readiness.reason ?: "Execution is offline" }
        status.config
    }
}

internal fun absoluteExecutionPath(path: String, platform: Platform = currentPlatform): Boolean {
    if ('\u0000' in path) return false
    if (platform != Platform.Windows) return path.startsWith("/")
    val normalized = path.replace('\\', '/')
    if (normalized.length >= 3 && normalized[0].isLetter() && normalized[1] == ':' && normalized[2] == '/') return true
    val parts = normalized.removePrefix("//").split('/')
    return normalized.startsWith("//") && parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()
}
