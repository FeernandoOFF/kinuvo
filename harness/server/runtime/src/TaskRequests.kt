package kinuvo.harness.server

import kinuvo.protocol.*
import kotlinx.datetime.LocalDate

/** Tasks, their lists and their providers' settings. */
internal class TaskRequests(private val harness: HarnessRuntime) {
    fun <C> register(handlers: RequestHandlers<C>) = with(handlers) {
        on(Api.TaskLists) { harness.tasks.lists() }
        on(Api.Tasks) { harness.tasks.all(it.includeDone) }
        on(Api.CreateTask) { p -> harness.tasks.create(p.title, p.list, p.notes, p.due?.let(LocalDate::parse), p.assignee) }
        on(Api.UpdateTask) { p -> harness.tasks.update(p.taskId, p.title, p.status, p.assignee, p.due) }
        on(Api.TasksSettings) { harness.tasksSetup() }
        on(Api.SaveTasksSettings) { harness.saveTasksSettings(it) }
        on(Api.ProviderLists) { harness.tasks.providerLists(it.provider) }
        on(Api.AuthorizeTasks) { harness.tasks.authorize(it.provider) }
    }
}
