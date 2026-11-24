package kinuvo.protocol.session

import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.execution.ExecutionPhase
import kotlinx.serialization.Serializable

/**
 * What a Session shows besides its entries: built from [AgentEvent]s, cleared as the matching entries land. The Server
 * folds all of them; clients fold only the items of a subscription, and a run's own text comes from its AG-UI events.
 */
@Serializable
data class LiveState(
    val running: Boolean = false,
    val queued: List<QueuedMessage> = emptyList(),
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
    /** Pending interactions whose answer is recorded and waits for the rest of the run's interactions. */
    val staged: Set<InteractionId> = emptySet(),
) {
    fun on(event: AgentEvent): LiveState = when (event) {
        is AgentEvent.QueueChanged -> copy(queued = event.messages)
        is AgentEvent.SubAgentChanged -> copy(children = children + (event.child.sessionId to event.child))
        is AgentEvent.InteractionRequested -> copy(pending = pending.filterNot { it.id == event.interaction.id } + event.interaction,
            interactionChildren = event.childSessionId?.let { interactionChildren + (event.interaction.id to it) } ?: interactionChildren)
        is AgentEvent.InteractionResolved -> copy(pending = pending.filterNot { it.id == event.id }, interactionChildren = interactionChildren - event.id, staged = staged - event.id)
        is AgentEvent.InteractionStaged -> if (pending.any { it.id == event.id }) copy(staged = staged + event.id) else this
        is AgentEvent.Idle -> copy(running = false, pending = pending.filter { it.id in interactionChildren }, staged = emptySet())
        is AgentEvent.ExecutionPhaseChanged -> copy(
            executionPhase = event.phase,
            running = event.phase == ExecutionPhase.Running || event.phase == ExecutionPhase.RequiresAction,
            pending = if (event.phase == ExecutionPhase.Lost) emptyList() else pending,
            staged = if (event.phase == ExecutionPhase.Lost) emptySet() else staged,
        )
        is AgentEvent.ExecutionEntryChanged -> {
            val key = requireNotNull(event.entry.identity()) { "External live entry needs a stable identity" }
            val index = entries.indexOfFirst { it.identity() == key }
            copy(entries = entries.toMutableList().apply { if (index < 0) add(event.entry) else set(index, event.entry) })
        }
        is AgentEvent.ReflectionStarted -> copy(reflectionId = event.id)
        is AgentEvent.RunStarted -> LiveState(running = true, queued = queued, reflectionId = reflectionId, executionPhase = executionPhase, pending = pending, entries = entries, children = children, interactionChildren = interactionChildren, staged = staged)
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
        is AgentEvent.RunFinished, is AgentEvent.RunError -> copy(running = false, pending = pending.filter { it.id in interactionChildren }, staged = emptySet())
        else -> this
    }

    /** The part a subscription snapshot carries; the active run's text and reasoning arrive as AG-UI events. */
    fun forSnapshot(): LiveState = copy(messageId = null, text = "", reasoning = "")

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
        is SessionEntry.Notice -> entry.child?.takeUnless { entry.code == "sub_agent_delivery" }?.let {
            copy(children = children + (it.sessionId to it))
        } ?: this
        is SessionEntry.Reflection -> if (reflectionId == entry.id) copy(reflectionId = null) else this
        is SessionEntry.AssistantMessage -> if (entry.messageId == null || entry.messageId == messageId) copy(text = "") else this
        is SessionEntry.Reasoning -> if (entry.messageId == null || entry.messageId == messageId) copy(reasoning = "") else this
        else -> this
    }
}
