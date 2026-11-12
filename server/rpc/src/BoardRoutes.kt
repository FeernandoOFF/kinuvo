package kinuvo.server.rpc

import kinuvo.protocol.Api
import kotlinx.datetime.LocalDate

internal fun RouteBuilder.boardRoutes() {
    device(Api.TaskLists) { server.harness.tasks.lists() }
    device(Api.Tasks) { server.harness.tasks.all(it.includeDone) }
    device(Api.CreateTask) { server.harness.tasks.create(it.title, it.list, it.notes, it.due?.let(LocalDate::parse), it.assignee) }
    device(Api.UpdateTask) { server.harness.tasks.update(it.taskId, it.title, it.status, it.assignee, it.due) }
    handle(Api.TasksSettings) { server.harness.tasksSetup() }
    handle(Api.SaveTasksSettings) { server.harness.saveTasksSettings(it.settings, it.secretUpdates) }
    handle(Api.ProviderLists) { server.harness.taskProviderLists(it) }
    handle(Api.AuthorizeTasks) { server.harness.tasks.authorize(it.provider) }
}
