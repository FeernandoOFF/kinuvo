package kinuvo.harness.core.store

import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionEntryIdentity
import kinuvo.protocol.identity

class SessionCommitConflict(val identity: String) : IllegalStateException("Conflicting saved Session entry '$identity'")

class CorruptSession(val sessionId: String, val line: Int, val trailing: Boolean, cause: Throwable) :
    IllegalStateException("Corrupt Session '$sessionId' at line $line${if (trailing) " (trailing record; recovery required)" else ""}", cause)

/** Validates the entire batch before writing anything; replay may overlap any saved positions. */
internal fun missingEntries(saved: List<SessionEntry>, incoming: List<SessionEntry>): List<SessionEntry> {
    val identities = mutableMapOf<SessionEntryIdentity, SessionEntry>()
    fun remember(entry: SessionEntry): Boolean {
        val key = entry.identity()?.takeUnless { entry is SessionEntry.Reasoning && entry.messageId == null } ?: return true
        val previous = identities[key]
        if (previous == null) identities[key] = entry
        if (previous != null && previous != entry) throw SessionCommitConflict("${key.type}:${key.id}")
        return previous == null
    }
    saved.forEach { remember(it) }
    return incoming.filter { remember(it) }
}

internal fun validateOwnerId(agentId: String) {
    require(SAFE_ID.matches(agentId)) { "Invalid Session owner ID" }
}

internal fun validateSessionId(sessionId: String) {
    require(SAFE_ID.matches(sessionId)) { "Invalid Session ID" }
}

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
