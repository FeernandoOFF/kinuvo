package kinuvo.server.execution

import kinuvo.harness.builtin.modelText

import kinuvo.harness.Agent
import kinuvo.server.SessionLog
import kinuvo.protocol.*

/** Uses the native log's atomic catch-up so persisted and live text retain their IDs. */
suspend fun SessionLog.executionSnapshot(agent: Agent, id: String) = executionSnapshot(agent.id, id)

suspend fun SessionLog.executionSnapshot(agent: String, id: String): ExecutionSnapshot {
    require(sessions(agent).any { it.id == id }) { "Session does not belong to source Agent" }
    session(agent, id)
    val messages = mutableListOf<ExecutionMessage>()
    var live = LiveState()
    var captured = false
    val subscriber = SessionLog.Subscriber { update ->
        if (captured) return@Subscriber true
        when (val change = update.change) {
            is SessionChange.Entry -> when (val entry = change.entry) {
                is SessionEntry.UserMessage -> messages.add(ExecutionMessage(entry.submissionId?.let { "user:$it" } ?: "user:entry:${change.seq}", "user", entry.modelText, ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
                is SessionEntry.AssistantMessage -> messages.add(ExecutionMessage(entry.messageId?.let { "assistant:$it" } ?: "assistant:entry:${change.seq}", "assistant", entry.text, ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
                is SessionEntry.Reasoning -> messages.add(ExecutionMessage(entry.messageId?.let { "reasoning:$it" } ?: "entry:${change.seq}", "reasoning", entry.text))
                is SessionEntry.VoiceCall -> messages.add(ExecutionMessage("voice_call:${entry.id}", "voice_call", "", ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
                is SessionEntry.Notice -> messages.add(ExecutionMessage("notice:${entry.id}", "notice", entry.message, ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
                is SessionEntry.ToolCall -> messages.add(ExecutionMessage("tool_call:${entry.id}", "tool", entry.name, ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
                is SessionEntry.ToolResult -> messages.add(ExecutionMessage("tool_result:${entry.id}", "tool", entry.output, ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
                else -> Unit
            }
            is SessionChange.Live -> { live = change.state; captured = true }
            else -> Unit
        }
        true
    }
    try { subscribe(agent, id, null, Int.MAX_VALUE, subscriber) }
    finally { unsubscribe(id, subscriber) }
    if (live.executionPhase != null) {
        live.entries.forEach { entry ->
            messages.add(ExecutionMessage(entry.identity()?.let { "${it.type}:${it.id}" } ?: "live", when(entry) {
                is SessionEntry.UserMessage -> "user"
                is SessionEntry.Reasoning -> "reasoning"
                is SessionEntry.ToolCall, is SessionEntry.ToolResult -> "tool"
                else -> "assistant"
            }, when(entry) {
                is SessionEntry.AssistantMessage -> entry.text
                is SessionEntry.Reasoning -> entry.text
                else -> ""
            }, ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
        }
    } else {
        if (live.text.isNotEmpty()) messages.add(ExecutionMessage("assistant:${live.messageId ?: "live"}", "assistant", live.text))
        if (live.reasoning.isNotEmpty()) messages.add(ExecutionMessage("reasoning:${live.messageId ?: "live"}", "reasoning", live.reasoning))
    }
    val requests = live.pending.map { if (it is PendingInteraction.Approval) it.forHarness() else it }
    return ExecutionSnapshot(id, live.executionPhase ?: if (requests.isNotEmpty()) ExecutionPhase.RequiresAction else if (live.running) ExecutionPhase.Running else ExecutionPhase.Idle, messages, requests, stopReason = when(val terminal = completion(id)) {
        is AgentEvent.RunError -> "error:${terminal.message}"
        is AgentEvent.RunFinished -> "completed"
        else -> null
    })
}

/** A linked Server and harness clients see an Approval as a harness permission request with Kinuvo's own options. */
internal fun PendingInteraction.Approval.forHarness() = PendingInteraction.HarnessApproval(id, toolCallName, reason, buildList {
    add(ExternalApprovalOption("allow_once", "Allow once", "allow_once"))
    if (trustable) add(ExternalApprovalOption("allow_session", "Allow for this Session", "allow_always"))
    if (trustable) add(ExternalApprovalOption("always_allow", "Always allow", "allow_always"))
    add(ExternalApprovalOption("deny", "Deny", "reject_once"))
})

internal fun approvalDecision(optionId: String?) = when (optionId) {
    "allow_once" -> ApprovalDecision.AllowOnce
    "allow_session" -> ApprovalDecision.AllowForSession
    "always_allow" -> ApprovalDecision.AlwaysAllow
    else -> ApprovalDecision.Deny()
}
