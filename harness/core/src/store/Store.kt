package kinuvo.harness.core.store

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo

/** Agents and their Sessions. */
interface Store {
    suspend fun agents(): List<Agent>
    /** A new Agent from the default template, or with [from]'s settings, Identity, Soul, Skills and MCP servers. */
    suspend fun createAgent(id: String, from: Agent? = null): Agent
    /** Deletes the Agent with all its data. */
    suspend fun deleteAgent(agent: Agent)
    suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent
    suspend fun sessions(agentId: String): List<SessionInfo>
    suspend fun session(agentId: String, sessionId: String): SessionInfo
    suspend fun createSession(agentId: String, instructions: String, routine: RoutineRef? = null): SessionInfo
    suspend fun append(agentId: String, sessionId: String, entry: SessionEntry)
    /** Atomically appends missing stable identities; conflicts throw SessionCommitConflict. Returns newly saved entries. */
    suspend fun appendAll(agentId: String, sessionId: String, entries: List<SessionEntry>): List<SessionEntry>
    suspend fun entries(agentId: String, sessionId: String): List<SessionEntry>
    suspend fun deleteSession(agentId: String, sessionId: String)
    /** Drops every entry from position [size] on. */
    suspend fun truncate(agentId: String, sessionId: String, size: Int)
}
