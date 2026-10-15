package kinuvo.harness.core.store

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Sessions kept only in memory, e.g. for a run whose transcript isn't worth saving. Agents can't be managed here. */
class InMemoryStore : Store {
    private val lock = Mutex()
    private val sessions = LinkedHashMap<String, MutableList<SessionEntry>>()

    override suspend fun agents(): List<Agent> = emptyList()
    override suspend fun createAgent(id: String, from: Agent?): Agent = unsupported()
    override suspend fun deleteAgent(agent: Agent) = unsupported()
    override suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent = unsupported()

    override suspend fun sessions(agent: Agent) = lock.withLock { sessions.keys.map { SessionInfo(it, agent.id, it) } }
    override suspend fun session(agent: Agent, sessionId: String) = SessionInfo(sessionId, agent.id, sessionId)

    override suspend fun createSession(agent: Agent, instructions: String, routine: RoutineRef?): SessionInfo {
        val id = Uuid.random().toString()
        lock.withLock { sessions[id] = mutableListOf(SessionEntry.Started(instructions, routine)) }
        return SessionInfo(id, agent.id, id, routine = routine)
    }

    override suspend fun append(agent: Agent, sessionId: String, entry: SessionEntry) = lock.withLock { sessions.getOrPut(sessionId) { mutableListOf() } += entry }
    override suspend fun entries(agent: Agent, sessionId: String) = lock.withLock { sessions[sessionId].orEmpty().toList() }
    override suspend fun deleteSession(agent: Agent, sessionId: String) = lock.withLock { sessions.remove(sessionId); Unit }
    override suspend fun truncate(agent: Agent, sessionId: String, size: Int) = lock.withLock {
        sessions[sessionId]?.let { while (it.size > size) it.removeAt(it.size - 1) }
        Unit
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("InMemoryStore only keeps Sessions")
}
