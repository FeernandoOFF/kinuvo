package kinuvo.protocol.session

import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.plugin.ToolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Events emitted during a run, named after AG-UI event types. */
@Serializable
sealed interface AgentEvent {
    /** The run is paused until the user answers via `session.respond`. */
    @Serializable @SerialName("INTERACTION_REQUESTED")
    data class InteractionRequested(val interaction: PendingInteraction) : AgentEvent

    /** [allowed] is set when an Approval was decided. */
    @Serializable @SerialName("INTERACTION_RESOLVED")
    data class InteractionResolved(val id: InteractionId, val allowed: Boolean? = null) : AgentEvent

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
    data class ToolCallStart(val toolCallId: ToolCallId, val toolCallName: String, val args: String, val identity: ToolIdentity? = null) : AgentEvent

    @Serializable @SerialName("TOOL_CALL_RESULT")
    data class ToolCallResult(val toolCallId: ToolCallId, val content: String, val isError: Boolean, val identity: ToolIdentity? = null) : AgentEvent

    @Serializable @SerialName("REFLECTION_STARTED")
    data class ReflectionStarted(val id: String) : AgentEvent

    /** Older turns of the Session were replaced by [summary] in what the model sees. */
    @Serializable @SerialName("COMPACTED")
    data class Compacted(val summary: String) : AgentEvent

    /** Non-fatal problem, e.g. an MCP server that could not start. */
    @Serializable @SerialName("WARNING")
    data class Warning(val message: String) : AgentEvent

    @Serializable @SerialName("RUN_FINISHED")
    data class RunFinished(val runId: RunId) : AgentEvent

    @Serializable @SerialName("RUN_ERROR")
    data class RunError(val runId: RunId, val message: String) : AgentEvent
}
