package kinuvo.protocol.session

import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.plugin.ToolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Events a Server run emits internally; the wire carries them as AG-UI run events or items (ADR 0042). */
@Serializable
sealed interface AgentEvent {
    @Serializable @SerialName("QUEUE_CHANGED")
    data class QueueChanged(val messages: List<QueuedMessage>) : AgentEvent

    @Serializable @SerialName("SUB_AGENT_CHANGED")
    data class SubAgentChanged(val child: SubAgentState) : AgentEvent

    /** The run is paused until the user answers via `session.respond`. */
    @Serializable @SerialName("INTERACTION_REQUESTED")
    data class InteractionRequested(val interaction: PendingInteraction, val childSessionId: SessionId? = null) : AgentEvent

    /** [allowed] is set when an Approval was decided. */
    @Serializable @SerialName("INTERACTION_RESOLVED")
    data class InteractionResolved(val id: InteractionId, val allowed: Boolean? = null) : AgentEvent

    /** [id]'s answer is recorded and waits for the rest of the run's interactions. */
    @Serializable @SerialName("INTERACTION_STAGED")
    data class InteractionStaged(val id: InteractionId) : AgentEvent

    /** [id]'s staged answer was not released, so it can be answered again. */
    @Serializable @SerialName("INTERACTION_UNSTAGED")
    data class InteractionUnstaged(val id: InteractionId) : AgentEvent

    /** The Session has no run and no work left, e.g. after Stop on a run that was waiting for answers. */
    @Serializable @SerialName("IDLE")
    data object Idle : AgentEvent

    @Serializable @SerialName("EXECUTION_PHASE_CHANGED")
    data class ExecutionPhaseChanged(val phase: ExecutionPhase) : AgentEvent

    /** Replace one mutable external item by typed stable identity, retaining its first-seen position. */
    @Serializable @SerialName("EXECUTION_ENTRY_CHANGED")
    data class ExecutionEntryChanged(val entry: SessionEntry) : AgentEvent

    @Serializable @SerialName("RUN_STARTED")
    data class RunStarted(val runId: RunId) : AgentEvent

    @Serializable @SerialName("TEXT_MESSAGE_CONTENT")
    data class TextMessageContent(val messageId: String, val delta: String) : AgentEvent

    @Serializable @SerialName("TEXT_MESSAGE_END")
    data class TextMessageEnd(val messageId: String, val text: String) : AgentEvent

    @Serializable @SerialName("THINKING_TEXT_MESSAGE_CONTENT")
    data class ThinkingContent(val messageId: String, val delta: String) : AgentEvent

    @Serializable @SerialName("TOOL_CALL_START")
    data class ToolCallStart(
        val toolCallId: ToolCallId, val toolCallName: String, val args: String, val identity: ToolIdentity? = null,
        /** The top-level call, e.g. a Script, that made this call; interactions raised under it park the run together. */
        val rootToolCallId: ToolCallId? = null,
    ) : AgentEvent

    /** The call's result, or with [progress] its live output so far, which the final result replaces. */
    @Serializable @SerialName("TOOL_CALL_RESULT")
    data class ToolCallResult(val toolCallId: ToolCallId, val content: String, val isError: Boolean, val identity: ToolIdentity? = null, val progress: Boolean = false) : AgentEvent

    @Serializable @SerialName("REFLECTION_STARTED")
    data class ReflectionStarted(val id: String) : AgentEvent

    /** Older turns of the Session were replaced by [summary] in what the model sees. */
    @Serializable @SerialName("COMPACTED")
    data class Compacted(val summary: String) : AgentEvent

    /** Non-fatal problem, e.g. an MCP server that could not start. */
    @Serializable @SerialName("WARNING")
    data class Warning(val message: String) : AgentEvent

    @Serializable @SerialName("RUN_FINISHED")
    data class RunFinished(val runId: RunId, val cancelled: Boolean = false) : AgentEvent

    @Serializable @SerialName("RUN_ERROR")
    data class RunError(val runId: RunId, val message: String) : AgentEvent
}
