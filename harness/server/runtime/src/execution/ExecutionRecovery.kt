package kinuvo.harness.server.execution

import kinuvo.protocol.*
import kotlinx.serialization.Serializable

@Serializable
data class ExecutionRecovery(
    val agentId: String,
    val sessionId: String,
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
    fun delete(agentId: String, sessionId: String)
}

class MemoryExecutionRecovery : ExecutionRecoveryStore {
    private val records = mutableMapOf<String, ExecutionRecovery>()
    override fun load() = records.values.toList()
    override fun save(record: ExecutionRecovery) { records[record.sessionId] = record }
    override fun delete(agentId: String, sessionId: String) { records.remove(sessionId) }
}
