package kinuvo.plugins.apple

import kinuvo.server.plugin.ProviderTask
import kinuvo.server.plugin.TaskApp
import kinuvo.server.plugin.TaskChange
import kinuvo.server.plugin.TaskList
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Apple Reminders as the user's task app; a due time is dropped, since a [ProviderTask] keeps a date. */
class AppleReminders(private val apple: Apple) : TaskApp {
    override suspend fun authorize() {
        val result = apple.authorize("reminders")
        check(result.status == "fullAccess") { result.message ?: "Reminders access is ${result.status}" }
    }

    override suspend fun lists() = apple.get<List<AppleList>>("reminders.lists").map { TaskList(it.id, it.title) }

    override suspend fun tasks(listId: String, includeDone: Boolean) = apple.get<List<AppleReminder>>("reminders.list", buildJsonObject {
        put("list", listId)
        put("include_done", includeDone)
        put("limit", 100_000)
    }).map { it.task() }

    override suspend fun task(id: String): ProviderTask? =
        try { apple.get<AppleReminder>("reminders.get", buildJsonObject { put("id", id) }).task() } catch (e: AppleException) { if (e.code == "notFound") null else throw e }

    override suspend fun create(listId: String, title: String, notes: String, due: LocalDate?) = apple.get<AppleReminder>("reminders.create", buildJsonObject {
        put("list", listId)
        put("title", title)
        put("notes", notes)
        due?.let { put("due", it.toString()) }
    }).task()

    override suspend fun update(taskId: String, change: TaskChange) = apple.get<AppleReminder>("reminders.update", buildJsonObject {
        put("id", taskId)
        change.title?.let { put("title", it) }
        change.notes?.let { put("notes", it) }
        if (change.clearDue) put("clear_due", true) else change.due?.let { put("due", it.toString()) }
        change.done?.let { put("done", it) }
    }).task()

    private fun AppleReminder.task() = ProviderTask(id, list_id, title, notes, due?.take(10)?.let(LocalDate::parse), done)
}
