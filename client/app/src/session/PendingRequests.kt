package kinuvo.app.session

import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.session.ToolCallId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What the open Session waits on from the user; answered through the respond, answer and answerExternal paths. */
sealed interface PendingRequest {
    val id: InteractionId

    /** A tool call waiting on an Approval; [event] has its tool, arguments, identity, risk, reason and trust hint. */
    data class Approval(val event: PendingInteraction.Approval) : PendingRequest {
        override val id get() = event.id
    }

    /** An `ask_user` form. */
    data class Questions(val asked: PendingInteraction.Questions) : PendingRequest {
        override val id get() = asked.id
    }

    /** A `request_takeover` call: [asked] is its note form, answered like [Questions]. */
    data class Takeover(val reason: String?, val asked: PendingInteraction.Questions) : PendingRequest {
        override val id get() = asked.id
    }

    /** An external Agent's harness prompt. */
    data class External(val prompt: PendingInteraction) : PendingRequest {
        override val id get() = prompt.id
    }
}

internal const val TAKEOVER_TOOL = "request_takeover"

/** An Agent's own requests in transcript order, those without a call in the transcript last, then an external harness's. */
internal fun pendingRequests(items: List<ChatItem>, pending: List<PendingInteraction>, external: Boolean): List<PendingRequest> {
    if (external) return pending.map { PendingRequest.External(it) }
    fun call(id: ToolCallId) = items.indexOfLast { it is ChatItem.ToolCall && it.callId == id && it.result == null }
    val native = pending.mapNotNull { request ->
        when (request) {
            is PendingInteraction.Approval -> call(request.toolCallId) to PendingRequest.Approval(request)
            is PendingInteraction.Questions -> {
                val index = call(request.toolCallId)
                val tool = items.getOrNull(index) as? ChatItem.ToolCall
                index to if (tool?.name == TAKEOVER_TOOL) PendingRequest.Takeover(takeoverReason(tool.args), request) else PendingRequest.Questions(request)
            }
            else -> null
        }
    }
    val harness = pending.filter { it !is PendingInteraction.Approval && it !is PendingInteraction.Questions }
    return native.sortedBy { if (it.first < 0) Int.MAX_VALUE else it.first }.map { it.second } + harness.map { PendingRequest.External(it) }
}

private fun takeoverReason(args: String) =
    runCatching { (Json.parseToJsonElement(args).jsonObject["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
