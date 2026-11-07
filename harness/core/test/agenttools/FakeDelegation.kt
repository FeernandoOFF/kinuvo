package kinuvo.harness.core.agenttools

import kinuvo.harness.core.store.Store
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionParent
import kotlinx.coroutines.CompletableDeferred

/** Children that answer when the test says so; each is a Session in [store] holding the goal it was given. */
class FakeDelegation(private val store: Store) : AgentDelegation {
    val started = mutableListOf<DelegateTask>()
    val stopped = mutableListOf<String>()
    val reviewed = mutableListOf<String>()
    var allow = true
    private val answers = mutableMapOf<String, CompletableDeferred<ChildResult>>()

    override suspend fun delegate(parent: CallerScope, task: DelegateTask): Child {
        val info = store.createSession(task.agentId, "")
        store.append(task.agentId, info.id, SessionEntry.Details(hidden = task.hidden, parent = SessionParent(parent.agentId, parent.sessionId!!, parent.depth + 1)))
        store.append(task.agentId, info.id, SessionEntry.UserMessage(task.message))
        started += task
        answers[info.id] = CompletableDeferred()
        return Child(task.agentId, info.id)
    }

    fun answer(sessionId: String, text: String, failed: Boolean = false) { answers.getValue(sessionId).complete(ChildResult(text, failed)) }

    override suspend fun outcome(child: Child) = answers.getValue(child.sessionId).await()

    override suspend fun stop(sessionId: String) {
        stopped += sessionId
        answers[sessionId]?.complete(ChildResult("Stopped."))
    }

    override suspend fun review(parent: CallerScope, callId: String?, child: Child, result: String): Boolean { reviewed += result; return allow }
}
