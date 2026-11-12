package kinuvo.server.execution

import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.surface.*
import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.server.ExecutionError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Source-side policy is evaluated on every call, including every snapshot and answer. */
class RemoteGateway(
    private val links: RemoteLinks,
    private val agents: suspend () -> List<AgentInfo>,
    private val create: suspend (String) -> ExecutionSnapshot,
    private val snapshot: suspend (String, String) -> ExecutionSnapshot,
    private val send: suspend (String, String, String, SurfaceAction?) -> Unit,
    private val cancel: suspend (String, String) -> Unit,
    private val answer: suspend (String, RespondParams) -> Boolean,
    private val discard: suspend (String, String) -> Unit = cancel,
) {
    context(_: Raise<ExecutionError>)
    suspend fun call(token: String, p: RemoteExecutionCall): RemoteExecutionResult {
        val exposed = links.exposure(token) ?: raise(ExecutionError.AccessRevoked)
        val available = agents().filter { it.id in exposed && it.executionKind != ExecutionKind.Remote }
        if (p.action == "list") return RemoteExecutionResult(agents = available)
        val agentId = requireNotNull(p.agentId) { "Select a source Agent" }
        if (available.none { it.id == agentId }) raise(ExecutionError.NotExposed)
        if (p.action == "create") {
            val created = create(agentId.value)
            try { links.claim(token, agentId, created.sessionId) }
            catch (e: Throwable) {
                withContext(NonCancellable) { discard(agentId.value, created.sessionId.value) }
                throw e
            }
            return RemoteExecutionResult(snapshot = created)
        }
        val sessionId = requireNotNull(p.sessionId)
        if (!links.owns(token, agentId, sessionId)) raise(ExecutionError.NotGranted)
        // Verify source Session ownership before cancellation or broker access.
        val current = snapshot(agentId.value, sessionId.value)
        return when (p.action) {
            "snapshot" -> RemoteExecutionResult(snapshot = current)
            "send" -> { send(agentId.value, sessionId.value, requireNotNull(p.text), p.surfaceAction); RemoteExecutionResult() }
            "cancel" -> { cancel(agentId.value, sessionId.value); RemoteExecutionResult() }
            "answer" -> RemoteExecutionResult(accepted = answer(agentId.value, RespondParams(sessionId, requireNotNull(p.requestId), requireNotNull(p.answer))))
            else -> error("Unsupported remote operation")
        }
    }
}
