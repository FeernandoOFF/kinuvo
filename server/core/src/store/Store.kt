package kinuvo.server.store

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.SessionStorage
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

/** Agents and their Sessions. */
interface Store : SessionStorage {
    suspend fun agents(): List<Agent>
    /** A new Agent from the default template, or with [from]'s settings, Identity, Soul, Skills and MCP servers. */
    suspend fun createAgent(id: String, from: Agent? = null): Agent
    /** Deletes the Agent with all its data. */
    suspend fun deleteAgent(agent: Agent)
    suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent
    suspend fun sessions(agentId: AgentId): List<SessionInfo>
    suspend fun session(agentId: AgentId, sessionId: SessionId): SessionInfo
    suspend fun createSession(agentId: AgentId, instructions: String, routine: RoutineRef? = null): SessionInfo
    /** Atomically appends missing stable identities; conflicts throw SessionCommitConflict. Returns newly saved entries. */
    suspend fun appendAll(agentId: AgentId, sessionId: SessionId, entries: List<SessionEntry>): List<SessionEntry>
    suspend fun deleteSession(agentId: AgentId, sessionId: SessionId)
    /** Drops every entry from position [size] on. */
    suspend fun truncate(agentId: AgentId, sessionId: SessionId, size: Int)
}
