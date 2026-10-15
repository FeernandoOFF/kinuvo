package kinuvo.protocol

import kotlinx.serialization.Serializable

/** Where a Task stands on the Board. [Waiting] means it needs the user. */
@Serializable
enum class TaskStatus { Todo, InProgress, Waiting, Done }

/**
 * A Task on the Board: what the user's task app stores ([title], [notes], [due] as an ISO date, done as [TaskStatus.Done])
 * plus what Kinuvo adds: [status], the [assignee] Agent and the [sessionIds] of its runs.
 */
@Serializable
data class TaskInfo(
    val id: String,
    val listId: String,
    val listName: String,
    val title: String,
    val notes: String = "",
    val due: String? = null,
    val status: TaskStatus = TaskStatus.Todo,
    val assignee: String? = null,
    val sessionIds: List<String> = emptyList(),
)

@Serializable
data class TaskListInfo(val id: String, val name: String, val agentId: String? = null)

@Serializable
data class TasksParams(val includeDone: Boolean = false)

/** [list] is a list name or id; the default list when null. [due] is an ISO date. */
@Serializable
data class CreateTaskParams(val title: String, val list: String? = null, val notes: String = "", val due: String? = null, val assignee: String? = null)

/** Fields to change; null leaves one as it is. An empty [assignee] clears it; an empty [due] removes the date. */
@Serializable
data class UpdateTaskParams(
    val taskId: String,
    val title: String? = null,
    val status: TaskStatus? = null,
    val assignee: String? = null,
    val due: String? = null,
)

/**
 * `tasks.yaml`: the [provider] holding the user's Tasks, the [lists] the Board shows (list name → Agent id, "" for no
 * Agent; empty shows every list), the [defaultList] for new Tasks, and when Agents start a Task and check for new ones.
 */
@Serializable
data class TasksSettings(
    val provider: String = "local",
    val lists: Map<String, String> = emptyMap(),
    val defaultList: String? = null,
    val startBeforeDays: Int = 3,
    val pollMinutes: Int = 5,
)

/** The settings and the task providers this Server has. */
@Serializable
data class TasksSetup(val settings: TasksSettings, val providers: List<String>)

@Serializable
data class TaskProviderRef(val provider: String)
