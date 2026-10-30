package kinuvo.harness.core.store

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionOrder
import kinuvo.protocol.SessionSettings
import kinuvo.protocol.SessionInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid
import kotlin.time.Clock

/** Sessions kept only in memory, e.g. for a run whose transcript isn't worth saving. Agents can't be managed here. */
class InMemoryStore : Store {
    private val lock = Mutex()
    private val sessions = LinkedHashMap<String, MutableList<SessionEntry>>()

    override suspend fun agents(): List<Agent> = emptyList()
    override suspend fun createAgent(id: String, from: Agent?): Agent = unsupported()
    override suspend fun deleteAgent(agent: Agent) = unsupported()
    override suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent = unsupported()

    private val created = mutableMapOf<String, Long>()
    private val owners = mutableMapOf<String, String>()

    private fun validate(agentId: String, sessionId: String) {
        validateOwnerId(agentId)
        validateSessionId(sessionId)
        require(owners[sessionId] == null || owners[sessionId] == agentId) { "Session belongs to another owner" }
    }

    override suspend fun sessions(agentId: String) = lock.withLock {
        validateOwnerId(agentId)
        sessions.keys.filter { owners[it] == agentId }.map { info(agentId, it) }.sortedWith(SessionOrder)
    }
    override suspend fun session(agentId: String, sessionId: String) = lock.withLock {
        validate(agentId, sessionId)
        info(agentId, sessionId)
    }

    private fun info(agentId: String, sessionId: String): SessionInfo {
        val entries = sessions[sessionId].orEmpty()
        val details = entries.filterIsInstance<SessionEntry.Details>().lastOrNull()
        val routine = entries.filterIsInstance<SessionEntry.Started>().firstOrNull()?.routine
        return SessionInfo(sessionId, agentId, details?.title ?: routine?.name ?: entries.filterIsInstance<SessionEntry.UserMessage>().firstOrNull()?.text?.take(60) ?: FileStore.NEW_TITLE,
            routine = routine, settings = entries.filterIsInstance<SessionEntry.Settings>().lastOrNull()?.settings ?: SessionSettings(),
            answered = entries.any { it is SessionEntry.UserMessage && it.routine == null || it is SessionEntry.Outcome && it.report }, favorite = details?.favorite ?: false, createdAt = created[sessionId])
    }

    override suspend fun createSession(agentId: String, instructions: String, routine: RoutineRef?): SessionInfo = lock.withLock {
        validateOwnerId(agentId)
        val id = Uuid.random().toString()
        created[id] = Clock.System.now().toEpochMilliseconds()
        owners[id] = agentId
        sessions[id] = mutableListOf(SessionEntry.Started(instructions, routine))
        info(agentId, id)
    }

    override suspend fun append(agentId: String, sessionId: String, entry: SessionEntry) = lock.withLock {
        validate(agentId, sessionId)
        owners[sessionId] = agentId
        sessions.getOrPut(sessionId) { mutableListOf() } += entry
    }
    override suspend fun appendAll(agentId: String, sessionId: String, entries: List<SessionEntry>): List<SessionEntry> = lock.withLock {
        validate(agentId, sessionId)
        val added = missingEntries(sessions[sessionId].orEmpty(), entries)
        owners[sessionId] = agentId
        sessions.getOrPut(sessionId) { mutableListOf() }.addAll(added)
        added
    }
    override suspend fun entries(agentId: String, sessionId: String) = lock.withLock {
        validate(agentId, sessionId)
        sessions[sessionId].orEmpty().toList()
    }
    override suspend fun deleteSession(agentId: String, sessionId: String) = lock.withLock {
        validate(agentId, sessionId)
        sessions.remove(sessionId)
        owners.remove(sessionId)
        created.remove(sessionId)
        Unit
    }
    override suspend fun truncate(agentId: String, sessionId: String, size: Int) = lock.withLock {
        validate(agentId, sessionId)
        require(size >= 0)
        sessions[sessionId]?.let { while (it.size > size) it.removeAt(it.size - 1) }
        Unit
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("InMemoryStore only keeps Sessions")
}
