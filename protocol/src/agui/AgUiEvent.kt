package kinuvo.protocol.agui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val AG_UI_PROTOCOL_VERSION = "1.0"

/** The subset of AG-UI 1.0 events that Kinuvo run streams use (ADR 0042); field names follow its `schema.json`. */
@Serializable
sealed interface AgUiEvent {
    val metadata: JsonObject?

    @Serializable @SerialName("RUN_STARTED")
    data class RunStarted(
        val threadId: String, val runId: String, val protocolVersion: String = AG_UI_PROTOCOL_VERSION, val parentRunId: String? = null,
        override val metadata: JsonObject? = null,
    ) : AgUiEvent

    @Serializable @SerialName("RUN_FINISHED")
    data class RunFinished(
        val threadId: String, val runId: String, val outcome: RunOutcome? = null, val usage: List<TokenUsage>? = null,
        override val metadata: JsonObject? = null,
    ) : AgUiEvent

    @Serializable @SerialName("RUN_ERROR")
    data class RunError(val message: String, val code: String? = null, val usage: List<TokenUsage>? = null, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("TEXT_MESSAGE_START")
    data class TextMessageStart(val messageId: String, val role: TextRole? = null, val name: String? = null, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("TEXT_MESSAGE_CONTENT")
    data class TextMessageContent(val messageId: String, val delta: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("TEXT_MESSAGE_END")
    data class TextMessageEnd(val messageId: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("REASONING_START")
    data class ReasoningStart(val messageId: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("REASONING_MESSAGE_START")
    data class ReasoningMessageStart(val messageId: String, val role: String = "reasoning", override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("REASONING_MESSAGE_CONTENT")
    data class ReasoningMessageContent(val messageId: String, val delta: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("REASONING_MESSAGE_END")
    data class ReasoningMessageEnd(val messageId: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("REASONING_END")
    data class ReasoningEnd(val messageId: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("TOOL_CALL_START")
    data class ToolCallStart(val toolCallId: String, val toolCallName: String, val parentMessageId: String? = null, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("TOOL_CALL_ARGS")
    data class ToolCallArgs(val toolCallId: String, val delta: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("TOOL_CALL_END")
    data class ToolCallEnd(val toolCallId: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("TOOL_CALL_RESULT")
    data class ToolCallResult(val messageId: String, val toolCallId: String, val content: String, override val metadata: JsonObject? = null) : AgUiEvent

    @Serializable @SerialName("ACTIVITY_SNAPSHOT")
    data class ActivitySnapshot(
        val messageId: String, val activityType: String, val content: JsonObject, val replace: Boolean? = null, override val metadata: JsonObject? = null,
    ) : AgUiEvent

    @Serializable @SerialName("CUSTOM")
    data class Custom(val name: String, val value: JsonElement, override val metadata: JsonObject? = null) : AgUiEvent
}

@Serializable
enum class TextRole {
    @SerialName("developer") Developer,
    @SerialName("system") System,
    @SerialName("assistant") Assistant,
    @SerialName("user") User,
}

@Serializable
sealed interface RunOutcome {
    @Serializable @SerialName("success")
    data class Success(val pendingToolCallIds: List<String>? = null) : RunOutcome

    @Serializable @SerialName("interrupt")
    data class Interrupted(val interrupts: List<Interrupt>) : RunOutcome {
        init { require(interrupts.isNotEmpty()) { "An interrupt outcome needs at least one interrupt" } }
    }

    @Serializable @SerialName("cancelled")
    data object Cancelled : RunOutcome
}

@Serializable
data class Interrupt(
    val id: String,
    val reason: String,
    val message: String? = null,
    val toolCallId: String? = null,
    val responseSchema: JsonObject? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class TokenUsage(
    val provider: String? = null,
    val model: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
)
