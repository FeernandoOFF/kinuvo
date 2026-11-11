package kinuvo.server.execution

import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.agent.AgentId
import kotlinx.serialization.Serializable

@Serializable
data class ExecutionRecovery(
    val agentId: AgentId,
    val sessionId: SessionId,
    val connection: HarnessConnection,
    val registration: AgentRegistration,
    val generation: String,
    val externalSessionId: String? = null,
    val support: ExecutionCapabilities = ExecutionCapabilities(),
    val failure: String? = null,
    val closed: Boolean = false,
    val lost: Boolean = false,
    val archived: Boolean = false,
    val submissions: Map<String, String?> = emptyMap(),
    val acceptedSubmissions: Set<String> = emptySet(),
    val inputCheckpoint: SessionEntry.UserMessage? = null,
    val draft: List<SessionEntry> = emptyList(),
    val checkpoint: List<SessionEntry> = emptyList(),
    val legacyOrder: Long? = null,
    val migrationId: String? = null,
)

/** Host-supplied atomic persistence; tests need no filesystem or process adapter. */
interface ExecutionRecoveryStore {
    fun load(): List<ExecutionRecovery>
    fun save(record: ExecutionRecovery)
    fun delete(agentId: AgentId, sessionId: SessionId)
}

class MemoryExecutionRecovery : ExecutionRecoveryStore {
    private val records = mutableMapOf<SessionId, ExecutionRecovery>()
    override fun load() = records.values.toList()
    override fun save(record: ExecutionRecovery) { records[record.sessionId] = record }
    override fun delete(agentId: AgentId, sessionId: SessionId) { records.remove(sessionId) }
}
