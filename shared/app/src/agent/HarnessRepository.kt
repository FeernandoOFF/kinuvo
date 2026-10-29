package kinuvo.app.agent

import kinuvo.app.connection.*

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The selected Server's Harness connections and external Agents. [catalog] is null until loaded, and stays null when
 * the Server can't list them (an older Server, or a Device without management access), which hides them.
 * [probes] are the latest checks by connection ID, [checking] those running.
 */
data class HarnessesState(val catalog: ExecutionCatalog? = null, val probes: Map<String, ExecutionProbe> = emptyMap(), val checking: Set<String> = emptySet()) {
    val connections get() = catalog?.connections
}

class HarnessRepository(internal val connection: ConnectedServer) {
    val state: StateFlow<HarnessesState>
        field = MutableStateFlow(HarnessesState())
    private var attached: Job? = null

    internal fun attach(client: KinuvoClient) {
        detach()
        attached = connection.scope.launch {
            launch { client.events(Api.ExecutionCatalogChanged).collect { c -> state.update { it.copy(catalog = c) } } }
            client.state.collect { if (it is ConnectionState.Connected) load() }
        }
    }

    internal fun detach() {
        attached?.cancel()
        state.value = HarnessesState()
    }

    fun load() = connection.scope.launch {
        catching { connection.call { it.call(Api.ExecutionCatalog, Unit) } }.onSuccess { c -> state.update { it.copy(catalog = c) } }
    }

    /** Checks [config], saved or not, on the Server's machine; a saved one's readiness updates for every Device. */
    suspend fun probe(config: HarnessConnection): ExecutionProbe {
        state.update { it.copy(checking = it.checking + config.id) }
        try {
            return connection.call { it.call(Api.ProbeHarnessConnection, config) }.also { p -> state.update { it.copy(probes = it.probes + (config.id to p)) } }
        } finally {
            if (connection.isCurrent()) state.update { it.copy(checking = it.checking - config.id) }
        }
    }

    /** Checks the saved connection [id] again. */
    suspend fun check(id: String) {
        val config = state.value.connections?.firstOrNull { it.config.id == id }?.config ?: return
        probe(config)
    }

    suspend fun save(config: HarnessConnection) = connection.call { it.call(Api.SaveHarnessConnection, config) }.also { reload() }

    suspend fun delete(id: String) = connection.call { it.call(Api.DeleteHarnessConnection, ExecutionRef(id)) }.also { reload() }

    suspend fun unregister(id: String) = connection.call { it.call(Api.UnregisterExternalAgent, ExecutionRef(id)) }.also { reload() }

    suspend fun register(agent: AgentRegistration) = connection.call { it.call(Api.RegisterExternalAgent, agent) }.also { reload() }

    /**
     * Links another Server with a link code from it: keeps the code privately, checks which Agents it shares, then saves
     * [config]; nothing is saved to the catalog if the Server can't be reached or rejects the code.
     */
    suspend fun link(config: HarnessConnection, url: String, token: String): List<AgentInfo> {
        val linkId = (config.connection as ExecutionConnection.Remote).serverId
        connection.call { it.call(Api.SaveRemoteLink, SaveRemoteLink(linkId, url, token)) }
        val agents = discover(linkId)
        save(config)
        return agents
    }

    /** The Agents the linked Server [linkId] shares with this one. */
    suspend fun discover(linkId: String) = connection.call { it.call(Api.DiscoverRemote, ExecutionRef(linkId)) }.agents

    /** A link code another Server uses to run [agentIds]; making one again under [id] revokes the old one. */
    suspend fun share(id: String, agentIds: Set<String>) = connection.call { it.call(Api.GrantExecution, ExposureGrant(id, agentIds)) }

    suspend fun revoke(id: String) = connection.call { it.call(Api.RevokeExecution, ExecutionRef(id)) }

    /** What this Server shares now, by link code ID; the codes themselves aren't kept where they could be shown. */
    suspend fun grants() = connection.call { it.call(Api.ExecutionGrants, Unit) }

    private suspend fun reload() = connection.call { it.call(Api.ExecutionCatalog, Unit) }.let { c -> state.update { it.copy(catalog = c) } }
}
