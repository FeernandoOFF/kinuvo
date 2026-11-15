package kinuvo.server.rpc

import kinuvo.protocol.Api
import kotlinx.datetime.LocalDate

internal fun RouteBuilder.boardRoutes() {
    device(Api.TaskLists) { server.tasks.lists() }
    device(Api.Tasks) { server.tasks.all(it.includeDone) }
    device(Api.CreateTask) { server.tasks.create(it.title, it.list, it.notes, it.due?.let(LocalDate::parse), it.assignee) }
    device(Api.UpdateTask) { server.tasks.update(it.taskId, it.title, it.status, it.assignee, it.due) }
    handle(Api.TasksSettings) { server.settings.tasksSetup() }
    handle(Api.SaveTasksSettings) { server.settings.saveTasksSettings(it.settings, it.secretUpdates) }
    handle(Api.ProviderLists) { server.settings.taskProviderLists(it) }
    handle(Api.AuthorizeTasks) { server.tasks.authorize(it.provider) }
}
