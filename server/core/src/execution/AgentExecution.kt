package kinuvo.server.execution

import kinuvo.harness.ExecutionDispatch
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.agent.AgentId
import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.server.SubAgents
import kinuvo.server.AgentError
import kinuvo.server.ServerError
import kinuvo.server.SessionError
import kinuvo.server.throwing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The one way to run any Agent: sends each call to the built-in harness or to an external one, by who owns the Agent or Session. */
class AgentExecution(
    private val registry: ExecutionRegistry,
    private val external: ExecutionCoordinator,
    private val native: NativeExecution,
    private val management: Mutex,
) : ExecutionDispatch {
    var subAgents: SubAgents? = null
    private suspend fun forAgent(agentId: AgentId): ExecutionDispatch = if (registry.registration(agentId) != null) external else native

    private suspend fun forSession(sessionId: SessionId): ExecutionDispatch = if (external.owns(sessionId)) external else native

    context(_: Raise<AgentError>)
    suspend fun open(agentId: AgentId): SessionInfo =
        if (registry.registration(agentId) != null) management.withLock { external.create(agentId) } else native.open(agentId)

    override suspend fun create(agentId: AgentId): SessionInfo = throwing<AgentError, _> { open(agentId) }

    override suspend fun snapshot(agentId: AgentId, sessionId: SessionId) = forAgent(agentId).snapshot(agentId, sessionId)

    context(_: Raise<SessionError>, _: Raise<AgentError>)
    suspend fun snapshot(sessionId: SessionId): ExecutionSnapshot =
        if (external.owns(sessionId)) external.snapshot(sessionId) else native.snapshotOf(native.owner(sessionId) ?: raise(SessionError.Unknown), sessionId)

    override suspend fun answer(answer: RespondParams) = subAgents?.answer(answer) == true || forSession(answer.sessionId).answer(answer)

    override suspend fun send(params: SendParams): SendResult = throwing<ServerError, _> { send(params, null) }

    /** [catalogs] are the Surface catalogs of the client sending, for the built-in harness. */
    context(_: Raise<AgentError>, _: Raise<SessionError>)
    suspend fun send(params: SendParams, catalogs: List<UiCatalog>?, onEvent: suspend (AgentEvent) -> Unit = {}): SendResult =
        if (registry.registration(params.agentId) != null) management.withLock {
            params.sessionId?.let { subAgents?.begin(it) }
            external.send(params)
        }
        else native.send(params, catalogs, onEvent)

    override suspend fun stop(sessionId: SessionId) { subAgents?.cancelChildren(sessionId); forSession(sessionId).stop(sessionId) }
}
