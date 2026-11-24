package kinuvo.app.session

import kinuvo.protocol.agui.AgUiEvent
import kinuvo.protocol.agui.Interrupt
import kinuvo.protocol.agui.RunOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The active run as AG-UI events build it, before its items land in history. Cleared as entries commit. */
data class AgUiRunState(
    val threadId: String? = null,
    val runId: String? = null,
    val parentRunId: String? = null,
    val messages: List<Message> = emptyList(),
    val tools: List<ToolCall> = emptyList(),
    val activities: List<Activity> = emptyList(),
    val interrupts: List<Interrupt> = emptyList(),
    val outcome: RunOutcome? = null,
    val error: String? = null,
) {
    val running: Boolean get() = runId != null && outcome == null && error == null

    data class Message(val id: String, val reasoning: Boolean, val text: String = "", val open: Boolean = true)

    data class ToolCall(
        val id: String, val name: String = "", val args: String = "", val open: Boolean = true, val result: String? = null, val isError: Boolean = false,
    )

    data class Activity(val id: String, val type: String, val content: JsonObject)

    /** Folds [event]; items whose id is in [committed] are skipped, as is anything after the run ended. */
    fun on(event: AgUiEvent, committed: Set<String> = emptySet()): AgUiRunState {
        if (event is AgUiEvent.RunStarted) return AgUiRunState(event.threadId, event.runId, event.parentRunId)
        if (!running) return this
        return when (event) {
            is AgUiEvent.RunStarted -> this
            is AgUiEvent.RunFinished -> copy(outcome = event.outcome ?: RunOutcome.Success(), interrupts = (event.outcome as? RunOutcome.Interrupted)?.interrupts.orEmpty())
            is AgUiEvent.RunError -> copy(error = event.message)
            is AgUiEvent.TextMessageStart -> start(event.messageId, false, committed)
            is AgUiEvent.ReasoningMessageStart -> start(event.messageId, true, committed)
            is AgUiEvent.TextMessageContent -> append(event.messageId, false, event.delta, committed)
            is AgUiEvent.ReasoningMessageContent -> append(event.messageId, true, event.delta, committed)
            is AgUiEvent.TextMessageEnd -> close(event.messageId, false, event.metadata?.kinuvo()?.get("text")?.jsonPrimitive?.contentOrNull)
            is AgUiEvent.ReasoningMessageEnd -> close(event.messageId, true, null)
            is AgUiEvent.ReasoningStart, is AgUiEvent.ReasoningEnd, is AgUiEvent.Custom -> this
            is AgUiEvent.ToolCallStart -> if (event.toolCallId in committed) this else
                copy(tools = tools.filterNot { it.id == event.toolCallId } + ToolCall(event.toolCallId, event.toolCallName, event.metadata?.kinuvo()?.get("args")?.jsonPrimitive?.contentOrNull.orEmpty()))
            is AgUiEvent.ToolCallArgs -> tool(event.toolCallId, committed) { it.copy(args = if (event.metadata?.kinuvo()?.containsKey("args") == true) it.args else it.args + event.delta) }
            is AgUiEvent.ToolCallEnd -> tool(event.toolCallId, committed) { it.copy(open = false) }
            is AgUiEvent.ToolCallResult -> tool(event.toolCallId, committed) {
                it.copy(open = false, result = event.content, isError = event.metadata?.kinuvo()?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false)
            }
            is AgUiEvent.ActivitySnapshot -> if (event.messageId in committed) this else
                copy(activities = activities.filterNot { it.id == event.messageId } + Activity(event.messageId, event.activityType, event.content))
        }
    }

    private fun start(id: String, reasoning: Boolean, committed: Set<String>) =
        if (id in committed || messages.any { it.id == id && it.reasoning == reasoning }) this else copy(messages = messages + Message(id, reasoning))

    private fun append(id: String, reasoning: Boolean, delta: String, committed: Set<String>): AgUiRunState {
        if (id in committed) return this
        val base = if (messages.any { it.id == id && it.reasoning == reasoning }) this else start(id, reasoning, committed)
        return base.copy(messages = base.messages.map { if (it.id == id && it.reasoning == reasoning) it.copy(text = it.text + delta) else it })
    }

    private fun close(id: String, reasoning: Boolean, text: String?) =
        copy(messages = messages.map { if (it.id == id && it.reasoning == reasoning) it.copy(text = text ?: it.text, open = false) else it })

    private fun tool(id: String, committed: Set<String>, change: (ToolCall) -> ToolCall): AgUiRunState {
        if (id in committed) return this
        val known = tools.any { it.id == id }
        return copy(tools = if (known) tools.map { if (it.id == id) change(it) else it } else tools + change(ToolCall(id)))
    }
}

private fun JsonObject.kinuvo(): JsonObject? = get("kinuvo")?.jsonObject
