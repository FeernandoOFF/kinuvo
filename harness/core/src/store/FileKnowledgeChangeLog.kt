package kinuvo.harness.core.store

import kinuvo.harness.core.knowledge.KnowledgeChangeLog
import kinuvo.protocol.KnowledgeChange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json

/** Knowledge changes as JSON lines in `knowledge-changes.jsonl`; undoing one rewrites its line. */
class FileKnowledgeChangeLog(private val file: Path) : KnowledgeChangeLog {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun add(change: KnowledgeChange) = lock.withLock {
        file.appendText(json.encodeToString(KnowledgeChange.serializer(), change) + "\n")
    }

    override suspend fun list(limit: Int): List<KnowledgeChange> = read().takeLast(limit).reversed()

    override suspend fun get(id: String): KnowledgeChange? = read().lastOrNull { it.id == id }

    override suspend fun markUndone(id: String) = lock.withLock {
        writeAtomically(file, read().joinToString("") { json.encodeToString(KnowledgeChange.serializer(), if (it.id == id) it.copy(undone = true) else it) + "\n" })
    }

    // A line cut short by a crash is skipped rather than losing the whole history.
    private fun read() = file.readTextOrNull()?.lines()?.filter { it.isNotBlank() }
        ?.mapNotNull { line -> runCatching { json.decodeFromString(KnowledgeChange.serializer(), line) }.getOrNull() }.orEmpty()
}
