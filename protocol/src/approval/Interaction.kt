package kinuvo.protocol.approval

import kinuvo.protocol.execution.ExternalApprovalOption
import kinuvo.protocol.plugin.ToolIdentity
import kinuvo.protocol.screen.ScreenRef
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.LiveState
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * What a Session waits on from the user, whichever side owns it: a tool call's Approval, an `ask_user` form or an
 * external harness's request. Announced by [AgentEvent.InteractionRequested], held in [LiveState.pending] and answered
 * with one [InteractionAnswer] through `session.respond`.
 */
@Serializable
sealed interface PendingInteraction {
    val id: InteractionId

    /** A tool call paused until the user decides; [id] is answered with [InteractionAnswer.Approval]. */
    @Serializable @SerialName("approval")
    data class Approval(
        override val id: InteractionId,
        /** The model's ID of the tool call waiting on this Approval. */
        val toolCallId: ToolCallId,
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
        val preview: ChangePreview? = null,
    ) : PendingInteraction

    /** A form, such as `ask_user`'s or an external harness's; answered with [InteractionAnswer.Questions]. */
    @Serializable @SerialName("questions")
    data class Questions(
        override val id: InteractionId, val toolCallId: ToolCallId, val title: String?, val questions: List<Question>, val screen: ScreenRef? = null,
    ) : PendingInteraction

    /**
     * A permission request, answered with one of the harness's own [options]. [site] is the host of a page it would open,
     * never the full address, which may carry a sign-in token; [server] is the MCP server asking.
     */
    @Serializable @SerialName("harness_approval")
    data class HarnessApproval(
        override val id: InteractionId, val title: String, val description: String?, val options: List<ExternalApprovalOption>,
        val site: String? = null, val server: String? = null,
    ) : PendingInteraction {
        /** Where it leads and who asks, e.g. "Opens example.com · from slack". */
        val origin get() = listOfNotNull(site?.let { "Opens $it" }, server?.let { "from $it" }).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
    }

    /**
     * A request to open a page, e.g. to sign in to a service the harness uses. Nothing opens until the user chooses
     * to; [site] is what the card shows, never the full [url].
     */
    @Serializable @SerialName("open_link")
    data class OpenLink(override val id: InteractionId, val message: String, val url: String, val site: String) : PendingInteraction

    /** A request this client can't fill in; it can only be declined. */
    @Serializable @SerialName("unsupported")
    data class Unsupported(override val id: InteractionId, val message: String) : PendingInteraction
}

fun PendingInteraction.withId(id: InteractionId): PendingInteraction = when (this) {
    is PendingInteraction.Approval -> copy(id = id)
    is PendingInteraction.Questions -> copy(id = id)
    is PendingInteraction.HarnessApproval -> copy(id = id)
    is PendingInteraction.OpenLink -> copy(id = id)
    is PendingInteraction.Unsupported -> copy(id = id)
}

/** The user's answer to a [PendingInteraction], matching its kind. */
@Serializable
sealed interface InteractionAnswer {
    @Serializable @SerialName("approval")
    data class Approval(val decision: ApprovalDecision) : InteractionAnswer

    /** By [Question.id]; null = skipped. */
    @Serializable @SerialName("questions")
    data class Questions(val answers: Map<String, List<String>>? = null) : InteractionAnswer

    /** The harness's own answer to its request: `{optionId}` or null (cancelled) for permissions, `{opened}` for links, null to decline. */
    @Serializable @SerialName("harness")
    data class Harness(val answer: JsonElement) : InteractionAnswer
}

/** Answers [requestId] in [sessionId]; true when this call claimed it, false when it was answered already or is gone. */
@Serializable
data class RespondParams(val sessionId: SessionId, val requestId: InteractionId, val answer: InteractionAnswer)

val AgentEvent.approval: PendingInteraction.Approval? get() = (this as? AgentEvent.InteractionRequested)?.interaction as? PendingInteraction.Approval

val AgentEvent.questions: PendingInteraction.Questions? get() = (this as? AgentEvent.InteractionRequested)?.interaction as? PendingInteraction.Questions
