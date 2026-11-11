package kinuvo.app.connection

import kinuvo.app.session.*

import io.github.xxfast.kstore.KStore
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.board.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.session.*
import kotlinx.serialization.Serializable

/** A Session's saved entries, from seq [firstSeq]; its live turn isn't kept. */
@Serializable
data class CachedSession(val agentId: AgentId, val firstSeq: Int, val entries: List<SessionEntry>, val receivedAt: Map<String, Long> = emptyMap())

/** What this Device last saw of one Server, shown before it connects and while it can't. [syncedAt] is in epoch milliseconds. */
@Serializable
data class Snapshot(
    val syncedAt: Long,
    val protocol: Int = ProtocolVersion.Current,
    val agents: List<AgentInfo> = emptyList(),
    val capabilities: ServerCapabilities? = null,
    val lists: Map<AgentId, SessionList> = emptyMap(),
    val sessions: Map<SessionId, CachedSession> = emptyMap(),
    val routines: Map<AgentId, List<Routine>> = emptyMap(),
    val taskLists: List<TaskListInfo> = emptyList(),
    val tasks: List<TaskInfo>? = null,
)

/** [Snapshot]s kept on this Device, one per Server key. */
interface ClientCache {
    suspend fun read(server: String): Snapshot?
    suspend fun write(server: String, snapshot: Snapshot)
    suspend fun remove(server: String)
    /** Drops every Server's snapshot. */
    suspend fun clear()

    /** Keeps nothing, e.g. for the embedded Server, which is always at hand. */
    object None : ClientCache {
        override suspend fun read(server: String): Snapshot? = null
        override suspend fun write(server: String, snapshot: Snapshot) {}
        override suspend fun remove(server: String) {}
        override suspend fun clear() {}
    }
}

/** A [ClientCache] over one [KStore] per Server; a snapshot that fails to read, or is from another protocol version, is dropped. */
internal class StoreCache(private val open: (name: String) -> KStore<Snapshot>, private val wipe: suspend () -> Unit) : ClientCache {
    private val stores = mutableMapOf<String, KStore<Snapshot>>()

    private fun store(server: String) = stores.getOrPut(server) { open(fileName(server)) }

    override suspend fun read(server: String): Snapshot? {
        val read = catching { store(server).get() }
        val snapshot = read.getOrNull()
        if (read.isSuccess && (snapshot == null || snapshot.protocol == ProtocolVersion.Current)) return snapshot
        remove(server)
        return null
    }

    /** Falls back to leaving transcripts out, e.g. when browser storage is full. */
    override suspend fun write(server: String, snapshot: Snapshot) {
        if (catching { store(server).set(snapshot) }.isSuccess) return
        catching { store(server).set(snapshot.copy(sessions = emptyMap())) }
    }

    override suspend fun remove(server: String) { catching { store(server).delete() } }

    override suspend fun clear() {
        stores.clear()
        wipe()
    }

    private fun fileName(server: String) =
        server.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").take(80) + "-" + server.hashCode().toUInt().toString(16)
}

/** This platform's [ClientCache]: JSON files in [dir], or browser storage on the web, where [dir] is ignored. */
expect fun deviceCache(dir: String): ClientCache
