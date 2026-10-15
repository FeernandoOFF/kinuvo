package kinuvo.harness.core.store

import kinuvo.harness.core.tasks.BoardRepository
import kinuvo.harness.core.tasks.TaskState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The Board's state in `board.json`. */
class FileBoard(private val file: Path) : BoardRepository {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = MapSerializer(String.serializer(), TaskState.serializer())

    override suspend fun get(): Map<String, TaskState> = file.readTextOrNull()?.let { json.decodeFromString(serializer, it) }.orEmpty()

    override suspend fun update(transform: (Map<String, TaskState>) -> Map<String, TaskState>) = lock.withLock {
        val old = get()
        val new = transform(old)
        if (new != old) writeAtomically(file, json.encodeToString(serializer, new))
    }
}
