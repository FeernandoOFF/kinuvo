package kinuvo.server

import kinuvo.protocol.*
import kinuvo.protocol.board.*
import kotlinx.datetime.LocalDate

/** Tasks, their lists and their providers' settings. */
internal class TaskRequests(private val harness: HarnessRuntime) {
    suspend fun handle(r: Frame.Request): Frame.Response? = when (r.method) {
        Api.TaskLists.name -> respond(r, Api.TaskLists) { harness.tasks.lists() }
        Api.Tasks.name -> respond(r, Api.Tasks) { harness.tasks.all(it.includeDone) }
        Api.CreateTask.name -> respond(r, Api.CreateTask) { p -> harness.tasks.create(p.title, p.list, p.notes, p.due?.let(LocalDate::parse), p.assignee) }
        Api.UpdateTask.name -> respond(r, Api.UpdateTask) { p -> harness.tasks.update(p.taskId, p.title, p.status, p.assignee, p.due) }
        Api.TasksSettings.name -> respond(r, Api.TasksSettings) { harness.tasksSetup() }
        Api.SaveTasksSettings.name -> respond(r, Api.SaveTasksSettings) { harness.saveTasksSettings(it.settings, it.secretUpdates) }
        Api.ProviderLists.name -> respond(r, Api.ProviderLists) { harness.taskProviderLists(it) }
        Api.AuthorizeTasks.name -> respond(r, Api.AuthorizeTasks) { harness.tasks.authorize(it.provider) }
        else -> null
    }
}
