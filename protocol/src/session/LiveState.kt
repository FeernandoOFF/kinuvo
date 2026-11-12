package kinuvo.protocol.session

import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.execution.ExecutionPhase
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
    val pending: List<PendingInteraction> = emptyList(),
    val executionPhase: ExecutionPhase? = null,
    val entries: List<SessionEntry> = emptyList(),
    val children: Map<SessionId, SubAgentState> = emptyMap(),
    val interactionChildren: Map<InteractionId, SessionId> = emptyMap(),
) {
    fun on(event: AgentEvent): LiveState = when (event) {
        is AgentEvent.SubAgentChanged -> copy(children = children + (event.child.sessionId to event.child))
        is AgentEvent.InteractionRequested -> copy(pending = pending.filterNot { it.id == event.interaction.id } + event.interaction,
            interactionChildren = event.childSessionId?.let { interactionChildren + (event.interaction.id to it) } ?: interactionChildren)
        is AgentEvent.InteractionResolved -> copy(pending = pending.filterNot { it.id == event.id }, interactionChildren = interactionChildren - event.id)
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
        is AgentEvent.RunStarted -> LiveState(running = true, reflectionId = reflectionId, executionPhase = executionPhase, pending = pending, entries = entries, children = children, interactionChildren = interactionChildren)
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
        is AgentEvent.RunFinished, is AgentEvent.RunError -> copy(running = false, pending = pending.filter { it.id in interactionChildren })
        else -> this
    }

    val status: SessionStatus
        get() = when {
            pending.isNotEmpty() -> SessionStatus.Waiting
            !running -> SessionStatus.Idle
            else -> SessionStatus.Running
        }

    fun on(entry: SessionEntry): LiveState {
        val keys = if (entry is SessionEntry.Notice) entry.draft.mapNotNull { it.identity() }.toSet() + listOfNotNull(entry.identity())
            else setOfNotNull(entry.identity())
        val reduced = if (keys.isEmpty()) this else copy(entries = entries.filterNot { it.identity() in keys })
        return reduced.saved(entry)
    }

    private fun saved(entry: SessionEntry): LiveState = when (entry) {
        is SessionEntry.Notice -> entry.child?.let { copy(children = children + (it.sessionId to it)) } ?: this
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
