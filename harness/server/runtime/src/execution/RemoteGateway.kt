package kinuvo.harness.server.execution

import kinuvo.protocol.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Source-side policy is evaluated on every call, including every snapshot and answer. */
class RemoteGateway(
    private val links: RemoteLinks,
    private val agents: suspend () -> List<AgentInfo>,
    private val create: suspend (String) -> ExecutionSnapshot,
    private val snapshot: suspend (String, String) -> ExecutionSnapshot,
    private val send: suspend (String, String, String) -> Unit,
    private val cancel: suspend (String, String) -> Unit,
    private val answer: suspend (String, ExecutionAnswer) -> Boolean,
    private val discard: suspend (String, String) -> Unit = cancel,
) {
    suspend fun call(token: String, p: RemoteExecutionCall): RemoteExecutionResult {
        val exposed = requireNotNull(links.exposure(token)) { "Remote access revoked" }
        val available = agents().filter { it.id in exposed && it.executionKind != ExecutionKind.Remote }
        if (p.action == "list") return RemoteExecutionResult(agents = available)
        val agentId = requireNotNull(p.agentId) { "Select a source Agent" }
        require(available.any { it.id == agentId }) { "Agent is not exposed" }
        if (p.action == "create") {
            val created = create(agentId)
            try { links.claim(token, agentId, created.sessionId) }
            catch (e: Throwable) {
                withContext(NonCancellable) { discard(agentId, created.sessionId) }
                throw e
            }
            return RemoteExecutionResult(snapshot = created)
        }
        val sessionId = requireNotNull(p.sessionId)
        require(links.owns(token, agentId, sessionId)) { "Session does not belong to this execution grant" }
        // Verify source Session ownership before cancellation or broker access.
        val current = snapshot(agentId, sessionId)
        return when (p.action) {
            "snapshot" -> RemoteExecutionResult(snapshot = current)
            "send" -> { send(agentId, sessionId, requireNotNull(p.text)); RemoteExecutionResult() }
            "cancel" -> { cancel(agentId, sessionId); RemoteExecutionResult() }
            "answer" -> RemoteExecutionResult(accepted = answer(agentId, ExecutionAnswer(sessionId, requireNotNull(p.requestId), requireNotNull(p.answer))))
            else -> error("Unsupported remote operation")
        }
    }
}
