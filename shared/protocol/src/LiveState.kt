package kinuvo.protocol

import kotlinx.serialization.Serializable

/**
 * What a running turn shows before it's persisted: built from [AgentEvent]s, cleared as the matching entries land.
 * Server and clients fold the same way, so a catch-up can hand over the state instead of replaying deltas.
 */
@Serializable
data class LiveState(
    val running: Boolean = false,
    val reflectionId: String? = null,
    /** The streaming message's ID, shared with its saved [SessionEntry.AssistantMessage] and [SessionEntry.Reasoning]. */
    val messageId: String? = null,
    val text: String = "",
    val reasoning: String = "",
    val approval: AgentEvent.ApprovalRequested? = null,
    val questions: AgentEvent.QuestionsAsked? = null,
) {
    fun on(event: AgentEvent): LiveState = when (event) {
        is AgentEvent.ReflectionStarted -> copy(reflectionId = event.id)
        is AgentEvent.RunStarted -> LiveState(running = true, reflectionId = reflectionId)
        is AgentEvent.TextMessageContent -> copy(messageId = event.messageId, text = text + event.delta)
        is AgentEvent.ThinkingContent -> copy(messageId = event.messageId, reasoning = reasoning + event.delta)
        is AgentEvent.ApprovalRequested -> copy(approval = event)
        is AgentEvent.ApprovalResolved -> copy(approval = null)
        is AgentEvent.QuestionsAsked -> copy(questions = event)
        is AgentEvent.QuestionsAnswered -> copy(questions = null)
        is AgentEvent.RunFinished, is AgentEvent.RunError -> copy(running = false, approval = null, questions = null)
        else -> this
    }

    val status: SessionStatus
        get() = when {
            !running -> SessionStatus.Idle
            approval != null || questions != null -> SessionStatus.Waiting
            else -> SessionStatus.Running
        }

    fun on(entry: SessionEntry): LiveState = when (entry) {
        is SessionEntry.Reflection -> if (reflectionId == entry.id) copy(reflectionId = null) else this
        is SessionEntry.AssistantMessage -> copy(text = "")
        is SessionEntry.Reasoning -> copy(reasoning = "")
        else -> this
    }
}

/** False for events whose content arrives as a [SessionEntry] instead. */
val AgentEvent.isLive: Boolean
    get() = this !is AgentEvent.ToolCallStart && this !is AgentEvent.ToolCallResult &&
        this !is AgentEvent.TextMessageEnd && this !is AgentEvent.Compacted
