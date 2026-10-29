package kinuvo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Events emitted during a run, named after AG-UI event types. */
@Serializable
sealed interface AgentEvent {
    @Serializable @SerialName("RUN_STARTED")
    data class RunStarted(val runId: String) : AgentEvent

    @Serializable @SerialName("TEXT_MESSAGE_CONTENT")
    data class TextMessageContent(val messageId: String, val delta: String) : AgentEvent

    @Serializable @SerialName("TEXT_MESSAGE_END")
    data class TextMessageEnd(val messageId: String, val text: String) : AgentEvent

    @Serializable @SerialName("THINKING_TEXT_MESSAGE_CONTENT")
    data class ThinkingContent(val messageId: String, val delta: String) : AgentEvent

    @Serializable @SerialName("TOOL_CALL_START")
    data class ToolCallStart(val toolCallId: String, val toolCallName: String, val args: String, val identity: ToolIdentity? = null) : AgentEvent

    /** The run is paused until the user answers via `approval.respond`. */
    @Serializable @SerialName("APPROVAL_REQUESTED")
    data class ApprovalRequested(
        /** Server-generated Approval ID, answered with [kinuvo.protocol.ApprovalParams]. */
        val approvalId: String,
        /** The model's ID of the tool call waiting on this Approval. */
        val toolCallId: String,
        val toolCallName: String,
        val args: String,
        /** What "Always allow" trusts (e.g. a shell prefix); null = the whole tool. */
        val trustHint: String? = null,
        val risk: Risk = Risk.Medium,
        /** Why the call has that [risk], e.g. "rm deletes files". */
        val reason: String? = null,
        /** False when "Always allow" can't be offered, e.g. for a command that can't be checked. */
        val trustable: Boolean = true,
        val identity: ToolIdentity? = null,
    ) : AgentEvent

    @Serializable @SerialName("APPROVAL_RESOLVED")
    data class ApprovalResolved(val toolCallId: String, val allowed: Boolean) : AgentEvent

    /** `ask_user` is waiting for the user's answers via `questions.respond`. */
    @Serializable @SerialName("QUESTIONS_ASKED")
    data class QuestionsAsked(val questionsId: String, val toolCallId: String, val title: String?, val questions: List<Question>, val screen: ScreenRef? = null) : AgentEvent

    @Serializable @SerialName("QUESTIONS_ANSWERED")
    data class QuestionsAnswered(val toolCallId: String) : AgentEvent

    @Serializable @SerialName("TOOL_CALL_RESULT")
    data class ToolCallResult(val toolCallId: String, val content: String, val isError: Boolean, val identity: ToolIdentity? = null) : AgentEvent

    @Serializable @SerialName("REFLECTION_STARTED")
    data class ReflectionStarted(val id: String) : AgentEvent

    /** Older turns of the Session were replaced by [summary] in what the model sees. */
    @Serializable @SerialName("COMPACTED")
    data class Compacted(val summary: String) : AgentEvent

    /** Non-fatal problem, e.g. an MCP server that could not start. */
    @Serializable @SerialName("WARNING")
    data class Warning(val message: String) : AgentEvent

    @Serializable @SerialName("RUN_FINISHED")
    data class RunFinished(val runId: String) : AgentEvent

    @Serializable @SerialName("RUN_ERROR")
    data class RunError(val runId: String, val message: String) : AgentEvent
}
