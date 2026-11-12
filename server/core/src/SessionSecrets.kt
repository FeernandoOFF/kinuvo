package kinuvo.server

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Values the user typed for `request_secret`, kept in memory only for [ttl] and resolved as `${secret://<id>}` only for
 * the Agent and Session that asked.
 */
class SessionSecrets(private val time: TimeSource = TimeSource.Monotonic, private val ttl: Duration = 24.hours) {
    private class Entry(val agentId: AgentId, val sessionId: SessionId, val value: String, val kept: TimeMark)
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    /** Keeps [value] and returns the reference the Agent gets. */
    suspend fun put(agentId: AgentId, sessionId: SessionId, value: String): String = lock.withLock {
        require(value.isNotEmpty()) { "Type a value or decline" }
        expire()
        val id = Uuid.random().toString()
        entries[id] = Entry(agentId, sessionId, value, time.markNow())
        "\${$SCHEME://$id}"
    }

    suspend fun resolve(uri: String, agentId: AgentId, sessionId: SessionId): String = lock.withLock {
        expire()
        entries[uri.removePrefix("$SCHEME://")]?.takeIf { it.agentId == agentId && it.sessionId == sessionId }?.value
            ?: error("Can't read $uri: it expired or belongs to another Session; ask again with request_secret")
    }

    suspend fun clear(sessionId: SessionId) = lock.withLock { entries.values.removeAll { it.sessionId == sessionId }; Unit }

    /** Every value still kept, for masking. */
    suspend fun values(): List<String> = lock.withLock { expire(); entries.values.map { it.value } }

    private fun expire() { entries.values.removeAll { it.kept.elapsedNow() >= ttl } }

    companion object {
        const val SCHEME = "secret"
    }
}
