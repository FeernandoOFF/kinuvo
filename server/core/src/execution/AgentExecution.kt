package kinuvo.server.execution

import kinuvo.harness.ExecutionDispatch
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.agent.AgentId
import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.server.AgentRuntime
import kinuvo.server.SubAgents
import kinuvo.server.SubAgentWake
import kinuvo.server.Runs
import kinuvo.server.SessionLog
import kinuvo.server.AgentError
import kinuvo.server.ServerError
import kinuvo.server.SessionError
import kinuvo.server.throwing
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The one way to run any Agent: sends each call to the built-in harness or to an external one, by who owns the Agent or Session. */
class AgentExecution(
    private val registry: ExecutionRegistry,
    private val external: ExecutionCoordinator,
    private val native: NativeExecution,
    private val management: Mutex,
    private val runs: Runs,
    private val log: SessionLog,
    private val harness: AgentRuntime,
) : ExecutionDispatch {
    var workflowSurface: suspend (AgentId, SessionId, SurfaceAction) -> Boolean = { _, _, _ -> false }
    var subAgents: SubAgents? = null
    private val finishListeners = mutableListOf<suspend (AgentId, SessionId, Boolean, String?) -> Unit>()

    init {
        external.onFinish = ::finished
        native.takeRetryWakes = { subAgents?.takeRetryWakes(it).orEmpty() }
        runs.onBegin = { agent, id, kind -> subAgents?.begin(agent, id, kind) }
        runs.onCancel = { subAgents?.cancelChildren(it) }
        runs.onFinish { agent, id, cancelled ->
            finished(agent, id, cancelled, (log.completion(id) as? AgentEvent.RunError)?.message)
        }
    }

    private suspend fun finished(agent: AgentId, id: SessionId, cancelled: Boolean, error: String?) {
        finishListeners.forEach { it(agent, id, cancelled, error) }
    }

    internal fun onFinish(listener: suspend (AgentId, SessionId, Boolean, String?) -> Unit) { finishListeners += listener }

    suspend fun join(id: SessionId) = if (external.owns(id)) external.join(id) else runs.join(id)

    internal suspend fun createChild(agent: AgentId, parent: SessionParent, settings: SessionSettings?): SessionInfo =
        if (registry.registration(agent) != null) management.withLock { external.create(agent, parent) }
        else log.createChild(agent, parent, settings)

    internal suspend fun trySteer(params: SendParams, allowed: suspend () -> Boolean, fallback: suspend () -> Unit): Boolean =
        !external.owns(requireNotNull(params.sessionId)) && native.trySteer(params, allowed, fallback)

    internal suspend fun report(agent: AgentId, id: SessionId, take: suspend () -> SubAgentWake?, restore: suspend (SubAgentWake, Exception?) -> Boolean) {
        if (!external.owns(id)) native.report(agent, id, take, restore)
        else {
            while (true) {
                external.join(id)
                harness.interactions.awaitClear(id)
                val sent = management.withLock {
                    if (external.isActive(id) || harness.interactions.open(id)) return@withLock false
                    val wake = take() ?: return
                    try {
                        external.send(SendParams(agent, id, wake.report.content), wake.saved,
                            onRejected = { restore(wake, it) }, onStopped = { restore(wake, null) }) {
                            subAgents?.begin(agent, id, Runs.Kind.Wake)
                        }
                    } catch (e: Exception) {
                        withContext(NonCancellable) { restore(wake, e) }
                        throw e
                    }
                    true
                }
                if (sent) return
            }
        }
    }

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

    /** Every answer, from any client, is staged in its run's interaction set and released with the rest of it. */
    override suspend fun answer(answer: RespondParams): Boolean {
        val target = subAgents?.target(answer)?.takeIf { child -> log.pending(child.sessionId).any { it.id == child.requestId } } ?: answer
        log.pending(target.sessionId).firstOrNull { it.id == target.requestId }?.let { harness.checkAnswer(it, target.answer, external.owns(target.sessionId)) }
        val parked = if (external.owns(target.sessionId)) external.isActive(target.sessionId) else runs.isActive(target.sessionId)
        return harness.interactions.respond(target, parked, ::release)
    }

    private suspend fun release(answer: RespondParams): Boolean {
        log.pending(answer.sessionId).firstOrNull { it.id == answer.requestId }?.let { if (harness.respondToTool(it, answer.answer)) return true }
        return forSession(answer.sessionId).answer(answer)
    }

    override suspend fun send(params: SendParams): SendResult = throwing<ServerError, _> { send(params, null) }

    internal suspend fun sendChild(params: SendParams): SendResult =
        throwing<ServerError, _> { send(params, null, Runs.Kind.Child) }

    /** [catalogs] are the Surface catalogs of the client sending, for the built-in harness. */
    context(_: Raise<AgentError>, _: Raise<SessionError>)
    suspend fun send(params: SendParams, catalogs: List<UiCatalog>?, kind: Runs.Kind = Runs.Kind.User, onEvent: suspend (AgentEvent) -> Unit = {}): SendResult {
        val session = params.sessionId
        val action = params.action
        if (session != null && action != null && workflowSurface(params.agentId, session, action))
            return SendResult(session, RunId(Uuid.random().toString()), params.text)
        return if (registry.registration(params.agentId) != null) management.withLock {
            external.send(params, null) { id -> subAgents?.begin(params.agentId, id, kind) }
        }
        else native.send(params, catalogs, kind, onEvent)
    }

    suspend fun cancelQueued(params: CancelQueuedParams) = native.cancelQueued(params)

    override suspend fun stop(sessionId: SessionId) {
        harness.interactions.stop(sessionId)
        if (external.owns(sessionId)) {
            subAgents?.cancelChildren(sessionId)
            external.stop(sessionId)
        } else {
            subAgents?.markStopped(sessionId)
            native.stop(sessionId)
            subAgents?.cancelChildren(sessionId)
        }
    }

    internal suspend fun delete(agent: AgentId, id: SessionId) {
        subAgents?.cancelChildren(id, deletingSession = true)
        if (external.owns(id)) management.withLock {
            try { withTimeoutOrNull(1_000) { external.stop(id) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Discard the connection even if the harness rejected cancellation. */ }
            external.delete(agent, id)
        } else {
            native.stop(id)
            runs.join(id)
        }
    }
}
