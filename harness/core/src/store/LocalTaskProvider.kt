package kinuvo.harness.core.store

import kinuvo.harness.core.tasks.ProviderTask
import kinuvo.harness.core.tasks.TaskChange
import kinuvo.harness.core.tasks.TaskList
import kinuvo.harness.core.tasks.TaskProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** Tasks kept by Kinuvo itself in `tasks.json`, for users without a task app. Starts with an Inbox list. */
class LocalTaskProvider(private val file: Path) : TaskProvider {
    override val id = "local"
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class Data(val lists: List<TaskList> = listOf(TaskList("inbox", "Inbox")), val tasks: List<Task> = emptyList())

    @Serializable
    private data class Task(val id: String, val listId: String, val title: String, val notes: String = "", val due: LocalDate? = null, val done: Boolean = false)

    override suspend fun lists(): List<TaskList> = read().lists

    override suspend fun tasks(listId: String, includeDone: Boolean): List<ProviderTask> =
        read().tasks.filter { it.listId == listId && (includeDone || !it.done) }.map { it.toProvider() }

    override suspend fun task(id: String): ProviderTask? = read().tasks.firstOrNull { it.id == id }?.toProvider()

    override suspend fun create(listId: String, title: String, notes: String, due: LocalDate?): ProviderTask = lock.withLock {
        val data = read()
        require(data.lists.any { it.id == listId }) { "No list '$listId'" }
        val task = Task(Uuid.random().toString(), listId, title, notes, due)
        write(data.copy(tasks = data.tasks + task))
        task.toProvider()
    }

    override suspend fun update(taskId: String, change: TaskChange): ProviderTask = lock.withLock {
        val data = read()
        val old = data.tasks.firstOrNull { it.id == taskId } ?: error("No task '$taskId'")
        val new = old.copy(
            title = change.title ?: old.title,
            notes = change.notes ?: old.notes,
            due = if (change.clearDue) null else change.due ?: old.due,
            done = change.done ?: old.done,
        )
        write(data.copy(tasks = data.tasks.map { if (it.id == taskId) new else it }))
        new.toProvider()
    }

    private fun read() = file.readTextOrNull()?.let { json.decodeFromString(Data.serializer(), it) } ?: Data()

    private fun write(data: Data) = writeAtomically(file, json.encodeToString(Data.serializer(), data))

    private fun Task.toProvider() = ProviderTask(id, listId, title, notes, due, done)
}
