package kinuvo.app.session

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ExecutionPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What the open Session waits on from the user; answered through the existing respond, answer and answerExternal paths. */
sealed interface PendingRequest {
    val id: String

    /** A tool call waiting on an Approval; [event] has its tool, arguments, identity, risk, reason and trust hint. */
    data class Approval(val event: AgentEvent.ApprovalRequested) : PendingRequest {
        override val id get() = event.approvalId
    }

    /** An `ask_user` form. */
    data class Questions(val asked: AgentEvent.QuestionsAsked) : PendingRequest {
        override val id get() = asked.questionsId
    }

    /** A `request_takeover` call: [asked] is its note form, answered like [Questions]. */
    data class Takeover(val reason: String?, val asked: AgentEvent.QuestionsAsked) : PendingRequest {
        override val id get() = asked.questionsId
    }

    /** An external Agent's harness prompt. */
    data class External(val prompt: ExecutionPrompt) : PendingRequest {
        override val id get() = prompt.id
    }
}

internal const val TAKEOVER_TOOL = "request_takeover"

/** Native requests in transcript order, those without a call in the transcript last, then the harness's own. */
internal fun pendingRequests(items: List<ChatItem>, approval: AgentEvent.ApprovalRequested?, questions: AgentEvent.QuestionsAsked?, prompts: List<ExecutionPrompt>): List<PendingRequest> {
    fun call(id: String) = items.indexOfLast { it is ChatItem.ToolCall && it.callId == id && it.result == null }
    val native = listOfNotNull(
        approval?.let { call(it.toolCallId) to PendingRequest.Approval(it) },
        questions?.let {
            val index = call(it.toolCallId)
            val tool = items.getOrNull(index) as? ChatItem.ToolCall
            index to if (tool?.name == TAKEOVER_TOOL) PendingRequest.Takeover(takeoverReason(tool.args), it) else PendingRequest.Questions(it)
        },
    )
    return native.sortedBy { if (it.first < 0) Int.MAX_VALUE else it.first }.map { it.second } + prompts.map { PendingRequest.External(it) }
}

private fun takeoverReason(args: String) =
    runCatching { (Json.parseToJsonElement(args).jsonObject["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
