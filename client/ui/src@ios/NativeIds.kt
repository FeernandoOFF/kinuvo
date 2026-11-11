package kinuvo.ui

import kinuvo.app.connection.AppViewModel
import kinuvo.app.navigation.Route
import kinuvo.app.session.ActiveCall
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.board.CreateTaskParams
import kinuvo.protocol.board.TaskInfo
import kinuvo.protocol.board.TaskStatus
import kinuvo.protocol.board.UpdateTaskParams
import kinuvo.protocol.session.SessionId

/** Objective-C export hides value classes, so SwiftUI reaches Agent, Session and assignee IDs as plain strings through here. */
object NativeIds {
    fun agent(info: AgentInfo): String = info.id.value
    fun agent(route: Route.Chat): String? = route.agentId?.value
    fun session(route: Route.Chat): String? = route.sessionId?.value
    fun agent(call: ActiveCall): String = call.agentId.value
    fun session(call: ActiveCall): String = call.sessionId.value
    fun sessions(task: TaskInfo): List<String> = task.sessionIds.map { it.value }
    fun assignee(task: TaskInfo): String? = task.assignee?.value
    fun createTask(title: String, assignee: String?) = CreateTaskParams(title, assignee = assignee?.let(::AgentId))
    fun updateStatus(task: TaskInfo, status: TaskStatus) = UpdateTaskParams(task.id, status = status)

    fun chat(agent: String?, session: String?): Route.Chat = Route.Chat(agent?.let(::AgentId), session?.let(::SessionId))
    fun lastView(app: AppViewModel, agent: String): Route.Chat = app.lastView(AgentId(agent))
    fun selectAgent(app: AppViewModel, agent: String, onOpen: (Route.Chat) -> Unit) = app.selectAgent(AgentId(agent), null, true, onOpen)
}
