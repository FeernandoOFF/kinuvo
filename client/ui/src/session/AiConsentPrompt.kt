package kinuvo.ui.session

import androidx.compose.runtime.Composable
import kinuvo.ui.design.ConfirmPopover

/** Asks before a message first goes to [provider], a third-party model provider, saying what it receives. */
@Composable
internal fun AiConsentPrompt(visible: Boolean, provider: String?, onAnswer: (Boolean) -> Unit) {
    val name = provider ?: "this Agent’s model provider"
    ConfirmPopover(
        visible, "Send your messages to $name?",
        "To reply, the Server sends $name your messages, attachments, the Agent’s Memory and the results of tools it uses, such as Contacts or files. " +
            "$name handles them under its own terms and privacy policy. Kinuvo asks once for each provider; Apple’s models need no permission. " +
            "See Settings → Privacy policy.",
        "Allow", destructive = false, { onAnswer(false) }, { onAnswer(true) },
    )
}

/** Names for providers when the Server's model catalog hasn't loaded. */
internal fun providerName(id: String): String? = when (id) {
    "" -> null
    "codex" -> "OpenAI (ChatGPT)"
    "openai" -> "OpenAI"
    "anthropic" -> "Anthropic"
    else -> id
}
