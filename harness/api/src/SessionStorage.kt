package kinuvo.harness

import kinuvo.protocol.SessionEntry

/** What the built-in harness reads and writes of a Session while it runs: its history, and what a turn and its compaction add. */
interface SessionStorage {
    suspend fun entries(agentId: String, sessionId: String): List<SessionEntry>
    suspend fun append(agentId: String, sessionId: String, entry: SessionEntry)
}
