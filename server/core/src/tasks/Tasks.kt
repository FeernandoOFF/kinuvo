package kinuvo.server.tasks

import kinuvo.server.plugin.ProviderTask
import kinuvo.server.plugin.TaskApp
import kinuvo.server.plugin.TaskChange
import kinuvo.server.plugin.TaskProvider
import kinuvo.server.plugin.NoPluginHost
import kinuvo.server.plugin.PluginHost
import kinuvo.server.update
import kinuvo.protocol.board.TaskInfo
import kinuvo.protocol.board.TaskListInfo
import kinuvo.protocol.board.TaskStatus
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * `config.yaml`'s `tasks`: which [provider] holds the user's Tasks and its [options] (e.g. an API token, as a Secret reference),
 * which [lists] the Board shows and the Agent each is assigned to (list name → Agent id), the [defaultList] new Tasks go to,
 * and how many days before its due date an Agent starts one.
 */
@Serializable
data class TasksConfig(
    val provider: String = "local",
    val lists: Map<String, String> = emptyMap(),
    val defaultList: String? = null,
    val startBeforeDays: Int = 3,
    val pollMinutes: Int = 5,
    val options: Map<String, String> = emptyMap(),
)

/** What Kinuvo keeps about a Task on top of the provider: status, an assignee set on the Board ("" = nobody), and its runs. */
@Serializable
data class TaskState(val status: TaskStatus = TaskStatus.Todo, val assignee: String? = null, val sessionIds: List<SessionId> = emptyList())

/** The Board's state per Task, keyed by `<provider id>:<task id>`. */
interface BoardRepository {
    suspend fun get(): Map<String, TaskState>
    /** Read-modify-write in one step. */
    suspend fun update(transform: (Map<String, TaskState>) -> Map<String, TaskState>)
}

/**
 * The Board: the user's Tasks from the task app the config names, with what Kinuvo adds. A Task's Agent is the one set
 * on the Board, else one `@mentioned` in its title or notes, else its list's. Done is the provider's; the other statuses
 * are Kinuvo's. Each operation uses one provider throughout, even if the config changes meanwhile.
 */
@OptIn(ExperimentalAtomicApi::class)
class Tasks(
    private val available: List<TaskProvider>,
    private val board: BoardRepository,
    private val config: () -> TasksConfig,
    private val agentIds: suspend () -> Set<String>,
    private val today: () -> LocalDate,
    /** The host [provider]'s task app reaches through, granted what [options] allow; built-in providers reach nothing. */
    private val host: (provider: TaskProvider, options: Map<String, String>) -> PluginHost = { _, _ -> NoPluginHost },
) {
    private val lock = Mutex()
    private val listeners = AtomicReference(emptyList<(TaskInfo) -> Unit>())

    fun onChange(listener: (TaskInfo) -> Unit) = listeners.update { it + listener }

    /** The provider's lists the Board shows: those in the config, or all of them when it names none. */
    suspend fun lists(): List<TaskListInfo> = lists(provider(), config())

    suspend fun all(includeDone: Boolean = false): List<TaskInfo> {
        val p = provider()
        val states = board.get()
        val agents = agentIds()
        // Blank reminders are left in the task app, but aren't Tasks.
        return lists(p, config()).flatMap { list -> p.tasks(list.id, includeDone).filter { it.title.isNotBlank() }.map { info(p, it, list, states[key(p, it.id)], agents) } }
    }

    suspend fun get(id: String): TaskInfo = get(provider(), id)

    /** Tasks an Agent should start now: to do, assigned, and due within the start window (or undated). */
    suspend fun ready(): List<TaskInfo> = all().filter(::isReady)

    suspend fun create(title: String, list: String? = null, notes: String = "", due: LocalDate? = null, assignee: String? = null): TaskInfo = lock.withLock {
        require(title.isNotBlank()) { "title is required" }
        assignee?.let { checkAgent(it) }
        val p = provider()
        val lists = lists(p, config())
        val target = (list ?: config().defaultList)?.let { l -> lists.firstOrNull { it.id == l || it.name.equals(l, true) } ?: error("No list '$l'; lists: ${lists.joinToString { it.name }}") }
            ?: lists.firstOrNull() ?: error("No task lists; add one in your task app or in Settings")
        val task = p.create(target.id, title.trim(), notes, due)
        if (assignee != null) board.update { it + (key(p, task.id) to TaskState(assignee = assignee)) }
        changed(p, task.id)
    }

    /**
     * Changes a Task. [assignee] "" leaves it with nobody; [due] "" removes the date. Setting [TaskStatus.Done] completes
     * it in the provider, and any other status reopens it.
     */
    suspend fun update(id: String, title: String? = null, status: TaskStatus? = null, assignee: String? = null, due: String? = null): TaskInfo = lock.withLock {
        val p = provider()
        val old = get(p, id)
        assignee?.let { checkAgent(it) }
        val change = TaskChange(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            due = due?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
            clearDue = due == "",
            done = status?.let { it == TaskStatus.Done }?.takeIf { it != (old.status == TaskStatus.Done) },
        )
        if (change != TaskChange()) p.update(id, change)
        if (status != null || assignee != null) board.update { states ->
            val s = states[key(p, id)] ?: TaskState()
            states + (key(p, id) to s.copy(status = status ?: s.status, assignee = assignee ?: s.assignee))
        }
        changed(p, id)
    }

    /** Appends a run's final reply to the Task's notes. */
    suspend fun appendReply(id: String, author: String, text: String): TaskInfo = lock.withLock {
        val p = provider()
        val old = p.task(id) ?: error("No task '$id'")
        p.update(id, TaskChange(notes = (old.notes.trimEnd() + "\n\n$author: ${text.trim()}").trim()))
        changed(p, id)
    }

    /** Marks [id] in progress if it's still ready; false when it no longer is, e.g. another run took it. */
    suspend fun claim(id: String): Boolean = lock.withLock {
        val p = provider()
        if (!isReady(get(p, id))) return false
        board.update { states -> states + (key(p, id) to (states[key(p, id)] ?: TaskState()).copy(status = TaskStatus.InProgress)) }
        changed(p, id)
        true
    }

    /** A run on [id] started in [sessionId]. */
    suspend fun started(id: String, sessionId: SessionId) = lock.withLock {
        val p = provider()
        board.update { states ->
            val s = states[key(p, id)] ?: TaskState(TaskStatus.InProgress)
            states + (key(p, id) to s.copy(sessionIds = s.sessionIds + sessionId))
        }
    }

    /** A run on [id] ended, or never started; a Task left in progress now waits for the user. */
    suspend fun finished(id: String) = lock.withLock {
        val p = provider()
        var moved = false
        board.update { states ->
            val s = states[key(p, id)]
            if (s?.status == TaskStatus.InProgress) states + (key(p, id) to s.copy(status = TaskStatus.Waiting)).also { moved = true } else states
        }
        if (moved) runCatching { changed(p, id) }
    }

    /** Tasks in progress with no run going (e.g. after a restart) wait for the user instead of staying stuck. */
    suspend fun reconcile(running: (TaskInfo) -> Boolean) {
        all().filter { it.status == TaskStatus.InProgress && !running(it) }.forEach { finished(it.id) }
    }

    /** The task providers this Server has. */
    val providers: List<TaskProvider> get() = available

    /**
     * Every list of the provider [id], shown on the Board or not, with [options] (null: the saved ones), e.g. to check
     * settings before they're saved.
     */
    suspend fun providerLists(id: String, options: Map<String, String>? = null): List<TaskListInfo> =
        provider(id, options).lists().map { TaskListInfo(it.id, it.name) }

    suspend fun authorize(id: String): List<TaskListInfo> {
        provider(id).authorize()
        return providerLists(id)
    }

    private fun provider(): Connected = provider(config().provider)

    private fun provider(id: String, options: Map<String, String>? = null): Connected {
        val p = available.firstOrNull { it.id == id } ?: error("No task provider '$id'; available: ${available.joinToString { it.id }}")
        val o = options ?: config().takeIf { it.provider == id }?.options.orEmpty()
        p.checkOptions(o)
        return Connected(id, p.configured(p.effectiveOptions(o), host(p, o)))
    }

    /** A provider's task app, with the [id] the Board keys its Tasks by. */
    private class Connected(val id: String, app: TaskApp) : TaskApp by app

    private suspend fun lists(p: Connected, config: TasksConfig): List<TaskListInfo> =
        p.lists().filter { config.lists.isEmpty() || it.name in config.lists || it.id in config.lists }
            .map { TaskListInfo(it.id, it.name, (config.lists[it.name] ?: config.lists[it.id])?.ifBlank { null }?.let(::AgentId)) }

    private suspend fun get(p: Connected, id: String): TaskInfo {
        val task = p.task(id) ?: error("No task '$id'")
        val list = lists(p, config()).firstOrNull { it.id == task.listId } ?: TaskListInfo(task.listId, task.listId)
        return info(p, task, list, board.get()[key(p, id)], agentIds())
    }

    private fun isReady(t: TaskInfo) = t.status == TaskStatus.Todo && t.assignee != null &&
        (t.due?.let { LocalDate.parse(it).minus(config().startBeforeDays, DateTimeUnit.DAY) <= today() } ?: true)

    private suspend fun checkAgent(id: String) = require(id.isEmpty() || id in agentIds()) { "No agent '$id'" }

    private suspend fun changed(p: Connected, id: String): TaskInfo = get(p, id).also { t -> listeners.load().forEach { it(t) } }

    private fun key(p: Connected, id: String) = "${p.id}:$id"

    private fun info(p: Connected, task: ProviderTask, list: TaskListInfo, state: TaskState?, agents: Set<String>): TaskInfo {
        val mentioned = MENTION.findAll("${task.title} ${task.notes}").map { it.groupValues[1] }.firstOrNull { it in agents }
        val assignee = when (state?.assignee) {
            null -> mentioned ?: list.agentId?.value
            "" -> null
            else -> state.assignee
        }
        val status = when {
            task.done -> TaskStatus.Done
            state == null || state.status == TaskStatus.Done -> TaskStatus.Todo
            else -> state.status
        }
        return TaskInfo(task.id, list.id, list.name, task.title, task.notes, task.due?.toString(), status, assignee, state?.sessionIds.orEmpty())
    }

    private companion object {
        val MENTION = Regex("""(?<![\w@-])@([a-z0-9][a-z0-9-]{0,31})(?![a-z0-9-])""")
    }
}
