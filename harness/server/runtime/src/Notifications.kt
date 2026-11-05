package kinuvo.harness.server

import io.github.oshai.kotlinlogging.KotlinLogging
import co.touchlab.stately.collections.ConcurrentMutableSet
import kinuvo.harness.core.plugin.Notification
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ExecutionPrompt
import kinuvo.protocol.RoutineRan
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.RunStatus
import kinuvo.protocol.ServiceKind
import kinuvo.protocol.ServiceTestResult
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.TestServiceParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger("kinuvo.notifications")

/**
 * Notifies the user about Sessions no client in the foreground is watching: an Approval or `ask_user` form waiting, a
 * reply or error, and a Routine's report or failure (a quiet run sends nothing).
 */
class Notifications(private val harness: HarnessRuntime, private val scope: CoroutineScope, private val routineOf: (sessionId: String) -> RoutineRef?, private val externalName: suspend (String) -> String? = { null }, private val devices: DevicePush? = null) {
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
                is AgentEvent.ApprovalRequested -> approvalText(name, event).let { (title, body) ->
                    Notification(title, body.clip(), agentId, sessionId, urgent = true)
                }
                is AgentEvent.QuestionsAsked ->
                    Notification("${name} has questions", (event.title ?: event.questions.joinToString(" · ") { it.label }).clip(), agentId, sessionId, urgent = true)
                // Routine runs are told by [routineRan], when they have something to report.
                is AgentEvent.RunFinished -> if (routine != null) null else Notification(name, plainText(lastReply(agentId, sessionId).orEmpty()).clip(), agentId, sessionId)
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
            val body = if (ran.status == RunStatus.Error) "Failed: ${ran.error}" else plainText(ran.summary.orEmpty())
            quietly(Notification(ran.routine.name, body.clip(), agent.id, ran.sessionId))
        }
    }

    /** A Routine found an MCP server it uses needs the user to sign in. */
    suspend fun mcpSignInNeeded(agentId: String, server: String) {
        quietly(Notification("MCP server '$server' needs sign-in", "Sign in from Settings → MCP servers or run `kinuvo mcp login $server`.", agentId))
    }

    /** Sends through the saved notifier, if any, and to subscribed Devices when pushes are on; failures are only logged, since a run doesn't care. */
    private suspend fun quietly(notification: Notification) {
        harness.notifier()?.let { notifier -> attempt { notifier.send(notification) } }
        devices?.takeIf { harness.pushNotifications }?.let { attempt { it.send(notification) } }
    }

    /** Tries [p]'s settings (the saved ones when null): pushes to subscribed Devices when they're on, and sends through the provider when there is one. */
    suspend fun test(p: TestServiceParams): ServiceTestResult {
        val settings = p.settings ?: harness.serviceSetup(ServiceKind.Notifications).settings
        val push = devices?.takeIf { settings.push } ?: return harness.testService(p)
        val pushed = try { push.send(Notification("Kinuvo", p.input?.ifBlank { null } ?: "Notifications from Kinuvo work.")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return ServiceTestResult(error = harness.secrets.redact(e.message ?: "Couldn't push")) }
        val devices = "${pushed.delivered} Device" + if (pushed.delivered == 1) "" else "s"
        val failed = pushed.failures.takeIf { it.isNotEmpty() }?.let { "Couldn't push to ${it.joinToString("; ")}" }
        if (settings.provider == null) return when {
            pushed.delivered > 0 -> failed?.let { ServiceTestResult(error = "Pushed to $devices. $it") } ?: ServiceTestResult("Pushed to $devices")
            else -> ServiceTestResult(error = failed ?: "No paired Device receives pushes yet")
        }
        return harness.testService(p).let { result ->
            val summary = result.summary?.let { it + if (pushed.delivered > 0) ", and pushed to $devices" else "" }
            if (result.error == null && failed == null) result.copy(summary = summary)
            else ServiceTestResult(error = listOfNotNull(result.error ?: summary, if (result.error != null && pushed.delivered > 0) "Pushed to $devices" else null, failed).joinToString(". "))
        }
    }

    private suspend fun attempt(send: suspend () -> Unit) {
        try {
            send()
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
