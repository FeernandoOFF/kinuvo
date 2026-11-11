package kinuvo.server.execution

import kinuvo.harness.ExecutionFactory
import kinuvo.harness.RemoteHarness
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.server.Admin
import kinuvo.server.HarnessRuntime
import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.server.AccessError
import kinuvo.server.AgentError
import kinuvo.server.ExecutionError
import kinuvo.server.SessionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Harness connections, external Agents, remote links and the Agent list that merges them with native Agents. */
class ExecutionService internal constructor(
    private val harness: HarnessRuntime,
    private val admin: Admin,
    private val registry: ExecutionRegistry,
    private val external: ExecutionCoordinator,
    private val dispatch: AgentExecution,
    private val management: Mutex,
    private val factory: ExecutionFactory,
    private val remote: RemoteHarness?,
    private val remoteLinks: RemoteLinks,
    private val gateway: RemoteGateway,
    private val serverName: String,
    private val capabilities: () -> ServerCapabilities,
    private val sequence: () -> Long,
    private val announce: suspend (ExecutionCatalog, List<AgentInfo>) -> Unit,
) {
    suspend fun checkHarness(config: HarnessConnection): ExecutionProbe {
        val probe = try {
            withTimeoutOrNull(30_000) { factory.probe(config) }
                ?: ExecutionProbe(ExecutionReadiness(true, false, "Harness check timed out", "unavailable"))
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            ExecutionProbe(ExecutionReadiness(true, false, "Harness check timed out", "unavailable"))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ExecutionProbe(ExecutionReadiness(true, false, e.message ?: "Harness check failed", "unavailable"))
        }
        management.withLock {
            if (registry.catalog().connections.any { it.config == config }) {
                if (probe.readiness.online) registry.online(config.id)
                else registry.offline(config.id, probe.readiness.reason ?: "Unavailable", probe.readiness.reasonCode ?: "unavailable")
                catalogChanged()
            }
        }
        return probe
    }

    suspend fun catalogChanged() { announce(registry.catalog(), agents()) }

    suspend fun catalog() = registry.catalog()

    context(_: Raise<SessionError>)
    suspend fun resume(id: String) = management.withLock { external.resume(SessionId(id)) }

    suspend fun closeExecution(id: String) = management.withLock { external.closeExecution(SessionId(id)) }

    context(_: Raise<SessionError>, _: Raise<AgentError>)
    suspend fun snapshot(id: String) = dispatch.snapshot(SessionId(id))

    /** Deciding any Approval takes management access, however the answer is shaped. */
    context(_: Raise<AccessError>)
    suspend fun respond(answer: RespondParams, management: Boolean): Boolean {
        if (!management) {
            val pending = if (external.owns(answer.sessionId)) external.snapshot(answer.sessionId).pending else harness.log.pending(answer.sessionId)
            val approving = answer.answer is InteractionAnswer.Approval ||
                pending.any { p -> p.id == answer.requestId && (p is PendingInteraction.Approval || p is PendingInteraction.HarnessApproval) }
            if (approving) raise(AccessError.ManagementRequired)
        }
        harness.log.pending(answer.sessionId).firstOrNull { it.id == answer.requestId }?.let { pending ->
            if (harness.respondToTool(pending, answer.answer)) return true
        }
        return dispatch.answer(answer)
    }

    suspend fun grant(grant: ExposureGrant): String {
        require(grant.agentIds.all { id -> if (registry.registration(id) != null) registry.configured(id).connection !is ExecutionConnection.Remote else harness.store.agents().any { it.id == id } }) { "Only installed source execution Agents may be exposed" }
        return remoteLinks.grant(grant)
    }

    fun grants() = remoteLinks.grants()

    fun revoke(id: String) = remoteLinks.revoke(id)

    context(_: Raise<ExecutionError>)
    suspend fun saveRemoteLink(link: SaveRemoteLink) = management.withLock {
        val connections = registry.catalog().connections.filter { (it.config.connection as? ExecutionConnection.Remote)?.serverId == link.id }
        if (connections.any { external.inUse(it.config.id) }) raise(ExecutionError.ConnectionInUse)
        remoteLinks.save(link)
    }

    context(_: Raise<ExecutionError>)
    suspend fun discoverRemote(id: String) = (remote ?: raise(ExecutionError.NoRemoteServers)).discover(id)

    context(_: Raise<ExecutionError>)
    suspend fun remoteExecution(token: String, call: RemoteExecutionCall) = gateway.call(token, call)

    context(_: Raise<ExecutionError>)
    suspend fun saveConnection(config: HarnessConnection) = management.withLock {
        if (external.inUse(config.id)) raise(ExecutionError.ConnectionInUse)
        registry.save(config).also { catalogChanged() }
    }

    context(_: Raise<ExecutionError>)
    suspend fun deleteConnection(id: String) = management.withLock {
        if (external.inUse(id)) raise(ExecutionError.ConnectionInUse)
        registry.removeConnection(id).also { catalogChanged() }
    }

    context(_: Raise<ExecutionError>)
    suspend fun registerAgent(registration: AgentRegistration) = management.withLock {
        val existing = registry.registration(registration.id)
        if (existing != null && external.agentInUse(existing.id)) raise(ExecutionError.AgentInUse)
        if (harness.store.agents().any { it.id == registration.id }) raise(ExecutionError.NativeAgentId)
        registry.register(registration).also { external.restore(it.id); catalogChanged() }
    }

    context(_: Raise<ExecutionError>)
    suspend fun unregisterAgent(id: String) = management.withLock {
        val agent = registry.registration(AgentId(id))
        if (agent != null && external.agentInUse(agent.id)) raise(ExecutionError.AgentInUse)
        external.archive(AgentId(id))
        registry.removeAgent(AgentId(id)).also { catalogChanged() }
    }

    suspend fun agents(): List<AgentInfo> = admin.agents() + externalAgents()

    context(_: Raise<AgentError>)
    suspend fun agentDetails(id: AgentId): AgentDetails =
        external.registrationOrArchived(id)?.let { a ->
            AgentDetails(externalInfo(a), AgentSettings("", "", "", "external"), "", "", managed = true, cwd = a.cwd, remoteAgentId = a.remoteAgentId)
        } ?: admin.details(admin.agent(id))

    context(_: Raise<ExecutionError>, _: Raise<AgentError>)
    suspend fun createAgent(p: CreateAgentParams): AgentInfo {
        if (registry.catalog().agents.any { it.id == p.agentId }) raise(ExecutionError.ReservedAgentId)
        return admin.create(p)
    }

    context(_: Raise<AgentError>)
    suspend fun sessionCapabilities(p: CapabilitiesParams): SessionCapabilities {
        val registration = external.registrationOrArchived(p.agentId)
        return if (registration == null) harness.capabilities(admin.agent(p.agentId), p.sessionId, p.settings, capabilities(), sequence())
        else external.capabilities(p.agentId, p.sessionId, externalInfo(registration).readiness, capabilities(), sequence())
    }

    suspend fun externalAgents(): List<AgentInfo> =
        (registry.catalog().agents + external.archives()).distinctBy { it.id }.map { externalInfo(it) }

    suspend fun externalInfo(a: AgentRegistration): AgentInfo {
        val c = registry.catalog().connections.firstOrNull { it.config.id == a.connectionId }
        if (registry.registration(a.id) == null) return AgentInfo(a.id, a.name, "external", a.avatar, ExecutionKind.Acp, a.connectionId, readiness = ExecutionReadiness(true, false, "Agent registration archived", "archived"))
        requireNotNull(c)
        return AgentInfo(a.id, a.name, "external", a.avatar, when (c.config.connection) { is ExecutionConnection.Acp -> ExecutionKind.Acp; is ExecutionConnection.Remote -> ExecutionKind.Remote; ExecutionConnection.BuiltIn -> ExecutionKind.Native }, a.connectionId, c.config.host ?: serverName, c.readiness)
    }
}
