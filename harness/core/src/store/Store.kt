package kinuvo.harness.core.store

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo

/** Agents and their Sessions. */
interface Store {
    suspend fun rooms(): List<kinuvo.protocol.Room> = emptyList()
    suspend fun saveRoom(room: kinuvo.protocol.Room): Unit = error("Room persistence is unavailable")
    suspend fun roomSessions(roomId: String): List<kinuvo.protocol.RoomSession> = emptyList()
    suspend fun saveRoomSession(session: kinuvo.protocol.RoomSession): Unit = error("Room persistence is unavailable")

    suspend fun agents(): List<Agent>
    /** A new Agent from the default template, or with [from]'s settings, Identity, Soul, Skills and MCP servers. */
    suspend fun createAgent(id: String, from: Agent? = null): Agent
    /** Deletes the Agent with all its data. */
    suspend fun deleteAgent(agent: Agent)
    suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent
    suspend fun sessions(agent: Agent): List<SessionInfo>
    suspend fun session(agent: Agent, sessionId: String): SessionInfo
    suspend fun createSession(agent: Agent, instructions: String, routine: RoutineRef? = null, roomExecution: Boolean = false): SessionInfo
    suspend fun append(agent: Agent, sessionId: String, entry: SessionEntry)
    suspend fun entries(agent: Agent, sessionId: String): List<SessionEntry>
    suspend fun deleteSession(agent: Agent, sessionId: String)
    /** Drops every entry from position [size] on. */
    suspend fun truncate(agent: Agent, sessionId: String, size: Int)
}
