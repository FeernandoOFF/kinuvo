package kinuvo.app.board

import kinuvo.app.connection.*

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Board as last loaded; [tasks] is null until it first loads, [error] set when the task provider failed. */
data class BoardData(val lists: List<TaskListInfo> = emptyList(), val tasks: List<TaskInfo>? = null, val error: String? = null)

/** The connected Server's Board, kept current from its events; a Task that starts waiting on the user becomes a [Notice]. */
class BoardRepository(private val notifications: Notifications, internal val connection: ConnectedServer) {
    val state: StateFlow<BoardData>
        field = MutableStateFlow(BoardData())

    private val scope get() = connection.scope
    private val client get() = connection.client
    private var attached: Job? = null

    internal fun attach(client: KinuvoClient) {
        clear()
        attached = scope.launch {
            launch {
                client.events(Api.TaskChanged).collect { task ->
                    val old = state.value.tasks?.firstOrNull { it.id == task.id }
                    if (task.status == TaskStatus.Waiting && old?.status != TaskStatus.Waiting) notifications.post(Notice.TaskWaiting(task))
                    state.update { b -> b.copy(tasks = b.tasks?.let { all -> if (old == null) all + task else all.map { if (it.id == task.id) task else it } }) }
                }
            }
            client.state.collect { if (it is ConnectionState.Connected && state.value.tasks != null) load() }
        }
    }

    internal fun detach() { clear() }

    /** Loads every Task, done ones included, so the Board can show them on request. */
    fun load() = scope.launch {
        val c = client ?: return@launch
        val result = catching { c.call(Api.TaskLists, Unit) to c.call(Api.Tasks, TasksParams(includeDone = true)) }
        state.update { b ->
            result.fold({ (lists, tasks) -> BoardData(lists, tasks) }, { b.copy(tasks = b.tasks ?: emptyList(), error = it.message ?: it.toString()) })
        }
    }

    suspend fun create(params: CreateTaskParams) = connection.run { call { it.call(Api.CreateTask, params) }.also(::put) }

    suspend fun update(params: UpdateTaskParams) = connection.run { call { it.call(Api.UpdateTask, params) }.also(::put) }

    private fun put(task: TaskInfo) {
        state.update { b -> b.copy(tasks = b.tasks?.let { all -> if (all.any { it.id == task.id }) all.map { if (it.id == task.id) task else it } else all + task }) }
    }

    private suspend fun <T> call(block: suspend (KinuvoClient) -> T): T =
        connection.call(block)

    private fun clear() {
        attached?.cancel()
        state.value = BoardData()
    }
}
