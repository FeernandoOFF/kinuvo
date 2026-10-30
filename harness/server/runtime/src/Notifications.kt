package kinuvo.harness.server

import io.github.oshai.kotlinlogging.KotlinLogging
import co.touchlab.stately.collections.ConcurrentMutableSet
import kinuvo.harness.core.Notification
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ExecutionPrompt
import kinuvo.protocol.RoutineRan
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.RunStatus
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger("kinuvo.notifications")

/**
 * Notifies the user about Sessions no client in the foreground is watching: an Approval or `ask_user` form waiting, a
 * reply or error, and a Routine's report or failure (a quiet run sends nothing).
 */
class Notifications(private val harness: HarnessRuntime, private val scope: CoroutineScope, private val routineOf: (sessionId: String) -> RoutineRef?, private val externalName: suspend (String) -> String? = { null }) {
    private val attempted = ConcurrentMutableSet<String>()
    fun on(agentId: String, sessionId: String, event: AgentEvent) {
        if (event !is AgentEvent.ApprovalRequested && event !is AgentEvent.QuestionsAsked && event !is AgentEvent.RunFinished && event !is AgentEvent.RunError && event !is AgentEvent.ExecutionRequested && event !is AgentEvent.ExecutionResolved) return
        val semantic = when (event) {
            is AgentEvent.ExecutionRequested -> "request:${event.request.id}"
            is AgentEvent.RunFinished -> "finished:${event.runId}"
            is AgentEvent.RunError -> "error:${event.runId}"
            is AgentEvent.ExecutionResolved -> return
            else -> null
        }
        if (semantic != null && !attempted.add("$sessionId:$semantic")) return
        // Read now, while the run is still active.
        val routine = routineOf(sessionId)
        scope.launch {
            val agent = harness.store.agents().firstOrNull { it.id == agentId }
            val name = agent?.name ?: externalName(agentId) ?: return@launch
            if (harness.log.watched(sessionId)) return@launch
            val notification = when (event) {
                is AgentEvent.ApprovalRequested ->
                    Notification("${name} needs an Approval", "${event.toolCallName}: ${event.reason ?: event.args}".clip(), agentId, sessionId, urgent = true)
                is AgentEvent.QuestionsAsked ->
                    Notification("${name} has questions", (event.title ?: event.questions.joinToString(" · ") { it.label }).clip(), agentId, sessionId, urgent = true)
                // Routine runs are told by [routineRan], when they have something to report.
                is AgentEvent.RunFinished -> if (routine != null) null else Notification(name, lastReply(agentId, sessionId).orEmpty().clip(), agentId, sessionId)
                is AgentEvent.RunError -> if (routine != null) null else Notification("${name} hit an error", event.message.clip(), agentId, sessionId)
                is AgentEvent.ExecutionRequested -> Notification("$name needs a response", when(val prompt = event.request.prompt) {
                    is ExecutionPrompt.Approval -> prompt.title
                    is ExecutionPrompt.Questions -> prompt.asked.title ?: "Questions"
                    is ExecutionPrompt.OpenLink -> prompt.message
                    else -> "Execution is waiting"
                }.clip(), agentId, sessionId, urgent = true)
                else -> null
            }
            notification?.let { quietly(it) }
        }
    }

    fun routineRan(ran: RoutineRan) {
        scope.launch {
            val agent = harness.store.agents().firstOrNull { it.id == ran.agentId } ?: return@launch
            if (ran.sessionId?.let { harness.log.watched(it) } == true) return@launch
            val body = if (ran.status == RunStatus.Error) "Failed: ${ran.error}" else ran.summary.orEmpty()
            quietly(Notification(ran.routine.name, body.clip(), agent.id, ran.sessionId))
        }
    }

    /** A Routine found an MCP server it uses needs the user to sign in. */
    suspend fun mcpSignInNeeded(agentId: String, server: String) {
        quietly(Notification("MCP server '$server' needs sign-in", "Sign in from Settings → MCP servers or run `kinuvo mcp login $server`.", agentId))
    }

    /** Sends through the saved notifier, if any; failures are only logged, since a run doesn't care. */
    private suspend fun quietly(notification: Notification) {
        val notifier = harness.notifier() ?: return
        try {
            notifier.send(notification)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = harness.secrets.redact(e.stackTraceToString())
            log.warn { "Notification failed:\n$failure" }
        }
    }

    private suspend fun lastReply(agent: String, sessionId: String) =
        harness.store.entries(agent, sessionId).lastOrNull { it is SessionEntry.AssistantMessage }?.let { (it as SessionEntry.AssistantMessage).text }

    private fun String.clip() = trim().let { if (it.length > MAX) it.take(MAX - 1) + "…" else it }

    private companion object {
        const val MAX = 300
    }
}
