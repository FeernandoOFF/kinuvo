package kinuvo.server.store

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.withWrittenAt
import kinuvo.protocol.session.SessionOrder
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid
import kotlin.time.Clock

/** Sessions kept only in memory, e.g. for a run whose transcript isn't worth saving. Agents can't be managed here. */
class InMemoryStore(private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : Store {
    private val lock = Mutex()
    private val sessions = LinkedHashMap<SessionId, MutableList<SessionEntry>>()

    private var workflowData = WorkflowData()
    override suspend fun workflows() = lock.withLock { workflowData }
    override suspend fun saveWorkflows(data: WorkflowData) = lock.withLock { workflowData = data }

    override suspend fun agents(): List<Agent> = emptyList()
    override suspend fun createAgent(id: String, from: Agent?): Agent = unsupported()
    override suspend fun deleteAgent(agent: Agent) = unsupported()
    override suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent = unsupported()

    private val created = mutableMapOf<SessionId, Long>()
    private val owners = mutableMapOf<SessionId, AgentId>()

    private fun validate(agentId: AgentId, sessionId: SessionId) {
        validateOwnerId(agentId)
        validateSessionId(sessionId)
        require(owners[sessionId] == null || owners[sessionId] == agentId) { "Session belongs to another owner" }
    }

    override suspend fun sessions(agentId: AgentId) = lock.withLock {
        validateOwnerId(agentId)
        sessions.keys.filter { owners[it]?.value == agentId.value }.map { info(agentId, it) }.sortedWith(SessionOrder)
    }
    override suspend fun session(agentId: AgentId, sessionId: SessionId) = lock.withLock {
        validate(agentId, sessionId)
        info(agentId, sessionId)
    }

    private fun info(agentId: AgentId, sessionId: SessionId): SessionInfo {
        val entries = sessions[sessionId].orEmpty()
        val details = entries.filterIsInstance<SessionEntry.Details>().lastOrNull()
        val routine = entries.filterIsInstance<SessionEntry.Started>().firstOrNull()?.routine
        return SessionInfo(sessionId, agentId, details?.title ?: routine?.name ?: entries.filterIsInstance<SessionEntry.UserMessage>().firstOrNull()?.text?.take(60) ?: FileStore.NEW_TITLE,
            routine = routine, settings = entries.filterIsInstance<SessionEntry.Settings>().lastOrNull()?.settings ?: SessionSettings(),
            answered = entries.any { it is SessionEntry.UserMessage && it.routine == null || it is SessionEntry.Outcome && it.report }, favorite = details?.favorite ?: false, createdAt = created[sessionId], parent = details?.parent, hidden = details?.hidden ?: false)
    }

    override suspend fun createSession(agentId: AgentId, instructions: String, routine: RoutineRef?): SessionInfo = lock.withLock {
        validateOwnerId(agentId)
        val id = Uuid.random().toString()
        created[SessionId(id)] = clock()
        owners[SessionId(id)] = agentId
        sessions[SessionId(id)] = mutableListOf(SessionEntry.Started(instructions, routine, writtenAt = clock()))
        info(agentId, SessionId(id))
    }

    override suspend fun append(agentId: AgentId, sessionId: SessionId, entry: SessionEntry) = lock.withLock {
        validate(agentId, sessionId)
        owners[sessionId] = agentId
        sessions.getOrPut(sessionId) { mutableListOf() } += entry.withWrittenAt(entry.writtenAt ?: clock())
    }
    override suspend fun appendAll(agentId: AgentId, sessionId: SessionId, entries: List<SessionEntry>): List<SessionEntry> = lock.withLock {
        validate(agentId, sessionId)
        val added = missingEntries(sessions[sessionId].orEmpty(), entries).map { it.withWrittenAt(it.writtenAt ?: clock()) }
        owners[sessionId] = agentId
        sessions.getOrPut(sessionId) { mutableListOf() }.addAll(added)
        added
    }
    override suspend fun entries(agentId: AgentId, sessionId: SessionId) = lock.withLock {
        validate(agentId, sessionId)
        sessions[sessionId].orEmpty().toList()
    }
    override suspend fun deleteSession(agentId: AgentId, sessionId: SessionId) = lock.withLock {
        validate(agentId, sessionId)
        sessions.remove(sessionId)
        owners.remove(sessionId)
        created.remove(sessionId)
        Unit
    }
    override suspend fun truncate(agentId: AgentId, sessionId: SessionId, size: Int) = lock.withLock {
        validate(agentId, sessionId)
        require(size >= 0)
        sessions[sessionId]?.let { while (it.size > size) it.removeAt(it.size - 1) }
        Unit
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("InMemoryStore only keeps Sessions")
}
