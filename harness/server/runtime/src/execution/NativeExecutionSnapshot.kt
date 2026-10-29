package kinuvo.harness.server.execution

import kinuvo.harness.core.Agent
import kinuvo.harness.server.SessionLog
import kinuvo.protocol.*

/** Uses the native log's atomic catch-up so persisted and live text retain their IDs. */
suspend fun SessionLog.executionSnapshot(agent: Agent, id: String): ExecutionSnapshot {
    require(sessions(agent).any { it.id == id }) { "Session does not belong to source Agent" }
    session(agent, id)
    val messages = mutableListOf<ExecutionMessage>()
    var live = LiveState()
    var captured = false
    val subscriber = SessionLog.Subscriber { update ->
        if (captured) return@Subscriber true
        when (val change = update.change) {
            is SessionChange.Entry -> when (val entry = change.entry) {
                is SessionEntry.UserMessage -> messages.add(ExecutionMessage("entry:${change.seq}", "user", entry.text))
                is SessionEntry.AssistantMessage -> messages.add(ExecutionMessage(entry.messageId ?: "entry:${change.seq}", "assistant", entry.text))
                is SessionEntry.Reasoning -> messages.add(ExecutionMessage(entry.messageId?.let { "reasoning:$it" } ?: "entry:${change.seq}", "reasoning", entry.text))
                is SessionEntry.ToolCall -> messages.add(ExecutionMessage(entry.id, "tool", entry.name, ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
                is SessionEntry.ToolResult -> messages.add(ExecutionMessage("result:${change.seq}", "tool", entry.output, ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), entry)))
                else -> Unit
            }
            is SessionChange.Live -> { live = change.state; captured = true }
            else -> Unit
        }
        true
    }
    try { subscribe(agent, id, null, Int.MAX_VALUE, subscriber) }
    finally { unsubscribe(id, subscriber) }
    if (live.text.isNotEmpty()) messages.add(ExecutionMessage(live.messageId ?: "live", "assistant", live.text))
    if (live.reasoning.isNotEmpty()) messages.add(ExecutionMessage("reasoning:${live.messageId ?: "live"}", "reasoning", live.reasoning))
    val requests = buildList {
        live.approval?.let { add(ExecutionRequest(it.approvalId, ExecutionRequestKind.Approval, ProtocolJson.encodeToJsonElement(AgentEvent.ApprovalRequested.serializer(), it), buildList {
            add(ExternalApprovalOption("allow_once", "Allow once", "allow_once"))
            if (it.trustable) add(ExternalApprovalOption("always_allow", "Always allow", "allow_always"))
            add(ExternalApprovalOption("deny", "Deny", "reject_once"))
        })) }
        live.questions?.let { add(ExecutionRequest(it.questionsId, ExecutionRequestKind.Question, ProtocolJson.encodeToJsonElement(AgentEvent.QuestionsAsked.serializer(), it))) }
    }
    return ExecutionSnapshot(id, if (requests.isNotEmpty()) ExecutionPhase.RequiresAction else if (live.running) ExecutionPhase.Running else ExecutionPhase.Idle, messages, requests)
}
