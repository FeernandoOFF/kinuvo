package kinuvo.server

import io.github.oshai.kotlinlogging.KotlinLogging
import co.touchlab.stately.collections.ConcurrentMutableSet
import kinuvo.server.plugin.Notification
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.routine.RoutineRan
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.routine.RunStatus
import kinuvo.protocol.service.ServiceKind
import kinuvo.protocol.service.ServiceTestResult
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.service.TestServiceParams
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger("kinuvo.notifications")

/**
 * Notifies the user about Sessions no client in the foreground is watching: an Approval or `ask_user` form waiting, a
 * reply or error, and a Routine's report or failure (a quiet run sends nothing).
 */
class Notifications(private val harness: HarnessRuntime, private val scope: CoroutineScope, private val routineOf: (sessionId: SessionId) -> RoutineRef?, private val externalName: suspend (String) -> String? = { null }, private val devices: DevicePush? = null) {
    private val attempted = ConcurrentMutableSet<String>()
    fun on(agentId: AgentId, sessionId: SessionId, event: AgentEvent) {
        if (event !is AgentEvent.InteractionRequested && event !is AgentEvent.RunFinished && event !is AgentEvent.RunError) return
        val semantic = when (event) {
            is AgentEvent.InteractionRequested -> "request:${event.interaction.id}"
            is AgentEvent.RunFinished -> "finished:${event.runId}"
            is AgentEvent.RunError -> "error:${event.runId}"
        }
        if (!attempted.add("$sessionId:$semantic")) return
        // Read now, while the run is still active.
        val routine = routineOf(sessionId)
        scope.launch {
            val agent = harness.store.agents().firstOrNull { it.id == agentId }
            val name = agent?.name ?: externalName(agentId.value) ?: return@launch
            if (harness.log.watched(sessionId)) return@launch
            val notification = when (event) {
                is AgentEvent.InteractionRequested -> when (val interaction = event.interaction) {
                    is PendingInteraction.Approval -> approvalText(name, interaction).let { (title, body) ->
                        Notification(title, body.clip(), agentId, sessionId, urgent = true)
                    }
                    is PendingInteraction.Questions ->
                        Notification("${name} has questions", (interaction.title ?: interaction.questions.joinToString(" · ") { it.label }).clip(), agentId, sessionId, urgent = true)
                    is PendingInteraction.HarnessApproval -> Notification("$name needs a response", interaction.title.clip(), agentId, sessionId, urgent = true)
                    is PendingInteraction.OpenLink -> Notification("$name needs a response", interaction.message.clip(), agentId, sessionId, urgent = true)
                    is PendingInteraction.Unsupported -> Notification("$name needs a response", "Execution is waiting".clip(), agentId, sessionId, urgent = true)
                }
                // Routine runs are told by [routineRan], when they have something to report.
                is AgentEvent.RunFinished -> if (routine != null) null else Notification(name, plainText(lastReply(agentId.value, sessionId).orEmpty()).clip(), agentId, sessionId)
                is AgentEvent.RunError -> if (routine != null) null else Notification("${name} hit an error", event.message.clip(), agentId, sessionId)
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
    suspend fun mcpSignInNeeded(agentId: AgentId, server: String) {
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

    private suspend fun lastReply(agent: String, sessionId: SessionId) =
        harness.store.entries(AgentId(agent), sessionId).lastOrNull { it is SessionEntry.AssistantMessage }?.let { (it as SessionEntry.AssistantMessage).text }

    private fun String.clip() = trim().let { if (it.length > MAX) it.take(MAX - 1) + "…" else it }

    private companion object {
        const val MAX = 300
    }
}
