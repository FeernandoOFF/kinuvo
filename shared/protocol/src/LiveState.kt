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
    val pending: List<ExecutionRequest> = emptyList(),
    val executionPhase: ExecutionPhase? = null,
    val entries: List<SessionEntry> = emptyList(),
) {
    fun on(event: AgentEvent): LiveState = when (event) {
        is AgentEvent.ExecutionRequested -> copy(pending = pending.filterNot { it.id == event.request.id } + event.request)
        is AgentEvent.ExecutionResolved -> copy(pending = pending.filterNot { it.id == event.requestId })
        is AgentEvent.ExecutionPhaseChanged -> copy(
            executionPhase = event.phase,
            running = event.phase == ExecutionPhase.Running || event.phase == ExecutionPhase.RequiresAction,
            pending = if (event.phase == ExecutionPhase.Lost) emptyList() else pending,
        )
        is AgentEvent.ExecutionEntryChanged -> {
            val key = requireNotNull(event.entry.identity()) { "External live entry needs a stable identity" }
            val index = entries.indexOfFirst { it.identity() == key }
            copy(entries = entries.toMutableList().apply { if (index < 0) add(event.entry) else set(index, event.entry) })
        }
        is AgentEvent.ReflectionStarted -> copy(reflectionId = event.id)
        is AgentEvent.RunStarted -> LiveState(running = true, reflectionId = reflectionId, executionPhase = executionPhase, pending = pending, entries = entries)
        is AgentEvent.TextMessageContent -> if (executionPhase == null) copy(messageId = event.messageId, text = text + event.delta)
            else {
                val previous = entries.filterIsInstance<SessionEntry.AssistantMessage>().firstOrNull { it.messageId == event.messageId }
                on(AgentEvent.ExecutionEntryChanged(SessionEntry.AssistantMessage(previous?.text.orEmpty() + event.delta, event.messageId)))
            }
        is AgentEvent.ThinkingContent -> if (executionPhase == null) copy(messageId = event.messageId, reasoning = reasoning + event.delta)
            else {
                val previous = entries.filterIsInstance<SessionEntry.Reasoning>().firstOrNull { it.messageId == event.messageId }
                on(AgentEvent.ExecutionEntryChanged(SessionEntry.Reasoning(previous?.text.orEmpty() + event.delta, messageId = event.messageId)))
            }
        is AgentEvent.ApprovalRequested -> copy(approval = event)
        is AgentEvent.ApprovalResolved -> copy(approval = null)
        is AgentEvent.QuestionsAsked -> copy(questions = event)
        is AgentEvent.QuestionsAnswered -> copy(questions = null)
        is AgentEvent.RunFinished, is AgentEvent.RunError -> copy(running = false, approval = null, questions = null, pending = emptyList())
        else -> this
    }

    val status: SessionStatus
        get() = when {
            !running -> SessionStatus.Idle
            approval != null || questions != null || pending.isNotEmpty() -> SessionStatus.Waiting
            else -> SessionStatus.Running
        }

    fun on(entry: SessionEntry): LiveState {
        val keys = if (entry is SessionEntry.Notice) entry.draft.mapNotNull { it.identity() }.toSet() + listOfNotNull(entry.identity())
            else setOfNotNull(entry.identity())
        val reduced = if (keys.isEmpty()) this else copy(entries = entries.filterNot { it.identity() in keys })
        return reduced.saved(entry)
    }

    private fun saved(entry: SessionEntry): LiveState = when (entry) {
        is SessionEntry.Reflection -> if (reflectionId == entry.id) copy(reflectionId = null) else this
        is SessionEntry.AssistantMessage -> if (entry.messageId == null || entry.messageId == messageId) copy(text = "") else this
        is SessionEntry.Reasoning -> if (entry.messageId == null || entry.messageId == messageId) copy(reasoning = "") else this
        else -> this
    }
}

/** False for events whose content arrives as a [SessionEntry] instead. */
val AgentEvent.isLive: Boolean
    get() = this !is AgentEvent.ToolCallStart && this !is AgentEvent.ToolCallResult &&
        this !is AgentEvent.TextMessageEnd && this !is AgentEvent.Compacted
