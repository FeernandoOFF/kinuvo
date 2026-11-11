package kinuvo.app.connection

import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.board.TaskInfo
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

/** Something worth telling the user about, even when they're looking elsewhere. */
sealed interface Notice {
    data class RunFinished(val agentId: AgentId, val sessionId: SessionId) : Notice
    data class ApprovalNeeded(val agentId: AgentId, val sessionId: SessionId) : Notice
    /** A Routine's run finished with something to show; [error] is set when it failed. */
    data class RoutineRan(val agentId: AgentId, val routine: RoutineRef, val sessionId: SessionId?, val error: String? = null) : Notice
    /** An Agent needs the user to move a Task on. */
    data class TaskWaiting(val task: TaskInfo) : Notice
}

/** One way of telling the user: a light vibration on phones now; push notifications or live activities later. Bind with `bind Notifier::class`. */
fun interface Notifier {
    fun notify(notice: Notice)
}

/** Sends each [Notice] to every bound [Notifier]; one failing doesn't stop the others. */
class Notifications(private val notifiers: List<Notifier>) {
    fun post(notice: Notice) = notifiers.forEach { runCatching { it.notify(notice) } }
}
