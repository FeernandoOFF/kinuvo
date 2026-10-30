package kinuvo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What an external Agent's harness waits on in a Session, as the Session screen shows it. */
@Serializable
sealed interface ExecutionPrompt {
    val id: String

    /**
     * A permission request, answered with one of the harness's own [options]. [site] is the host of a page it would open,
     * never the full address, which may carry a sign-in token; [server] is the MCP server asking.
     */
    @Serializable @SerialName("approval")
    data class Approval(
        override val id: String, val title: String, val description: String?, val options: List<ExternalApprovalOption>,
        val site: String? = null, val server: String? = null,
    ) : ExecutionPrompt {
        /** Where it leads and who asks, e.g. "Opens example.com · from slack". */
        val origin get() = listOfNotNull(site?.let { "Opens $it" }, server?.let { "from $it" }).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
    }

    /**
     * A form, shown like an `ask_user` form; [values] maps each option label back to the value the harness expects.
     * [native] marks a linked Kinuvo Server's own `ask_user` form, answered as Kinuvo answers it.
     */
    @Serializable @SerialName("questions")
    data class Questions(
        override val id: String, val asked: AgentEvent.QuestionsAsked, val types: Map<String, String> = emptyMap(),
        val values: Map<String, Map<String, String>> = emptyMap(), val native: Boolean = false,
    ) : ExecutionPrompt

    /**
     * A request to open a page, e.g. to sign in to a service the harness uses. Nothing opens until the user chooses
     * to; [site] is what the card shows, never the full [url].
     */
    @Serializable @SerialName("open_link")
    data class OpenLink(override val id: String, val message: String, val url: String, val site: String) : ExecutionPrompt

    /** A request this client can't fill in; it can only be declined. */
    @Serializable @SerialName("unsupported")
    data class Unsupported(override val id: String, val message: String) : ExecutionPrompt
}

fun ExecutionPrompt.withId(id: String): ExecutionPrompt = when (this) {
    is ExecutionPrompt.Approval -> copy(id = id)
    is ExecutionPrompt.Questions -> copy(id = id, asked = asked.copy(questionsId = id))
    is ExecutionPrompt.OpenLink -> copy(id = id)
    is ExecutionPrompt.Unsupported -> copy(id = id)
}
