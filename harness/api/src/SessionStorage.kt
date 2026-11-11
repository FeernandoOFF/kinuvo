package kinuvo.harness

import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

/** What the built-in harness reads and writes of a Session while it runs: its history, and what a turn and its compaction add. */
interface SessionStorage {
    suspend fun entries(agentId: AgentId, sessionId: SessionId): List<SessionEntry>
    suspend fun append(agentId: AgentId, sessionId: SessionId, entry: SessionEntry)
}
