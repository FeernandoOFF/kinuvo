package kinuvo.harness.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** A list in the user's task app, e.g. a Reminders list. */
@Serializable
data class TaskList(val id: String, val name: String)

/** A task as the user's task app stores it; [due] is a date, since not every app keeps a time. */
data class ProviderTask(
    val id: String,
    val listId: String,
    val title: String,
    val notes: String = "",
    val due: LocalDate? = null,
    val done: Boolean = false,
)

/** Fields to change; null leaves a field as it is, and [clearDue] removes the due date. */
data class TaskChange(
    val title: String? = null,
    val notes: String? = null,
    val due: LocalDate? = null,
    val clearDue: Boolean = false,
    val done: Boolean? = null,
)

/**
 * The user's task app, the source of truth for title, notes, due date and done. What Kinuvo adds on top (status,
 * assignee, Sessions) lives in the Board, not here. [id] is what `provider:` names in tasks.yaml.
 */
interface TaskProvider {
    val id: String
    suspend fun authorize() {}
    suspend fun lists(): List<TaskList>
    suspend fun tasks(listId: String, includeDone: Boolean = false): List<ProviderTask>
    /** One task by id, done or not; null when it doesn't exist. */
    suspend fun task(id: String): ProviderTask?
    suspend fun create(listId: String, title: String, notes: String = "", due: LocalDate? = null): ProviderTask
    suspend fun update(taskId: String, change: TaskChange): ProviderTask
}
