package kinuvo.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.connection.ProviderSignInState
import kinuvo.ui.design.*

const val ChatGptUsageUrl = "https://chatgpt.com/settings/usage"

/** What signing in with ChatGPT does and how far it got; the other ways to sign in stay folded under it. */
@Composable
internal fun ChatGptSignInRows(
    signIn: ProviderSignInState?,
    busy: Boolean,
    onContinue: () -> Unit,
    onPaste: (String) -> Unit,
    onCancel: () -> Unit,
    onCode: () -> Unit,
    /** Draws the Continue with ChatGPT button in the card; off where the screen's main button starts it. */
    button: Boolean = true,
    /** Switches to an OpenAI API key instead, where the screen offers one. */
    onApiKey: (() -> Unit)? = null,
) {
    val uri = LocalUriHandler.current
    var pasting by remember(signIn?.url) { mutableStateOf(false) }
    val pasteOpen = signIn?.awaitingPaste == true || pasting
    GroupBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space10)) {
            Icon(ChatGptLogo, Modifier.size(20.dp), tint = color(onBackgroundColor))
            Text(when {
                signIn == null -> "Use your ChatGPT plan"
                pasteOpen -> "Finish signing in"
                else -> "Finish in your browser"
            }, style = TextStyles.label.copy(fontWeight = FontWeight.SemiBold))
        }
        when {
            signIn == null -> {
                Text("Eligible requests use the usage included in your ChatGPT plan or credits. Manage it anytime in ChatGPT settings.",
                    color = color(mutedColor), style = TextStyles.secondary)
                if (button) ContinueWithChatGpt(onContinue, enabled = !busy)
            }
            signIn.url == null -> Waiting("Opening ChatGPT…")
            pasteOpen -> PasteCallback(onPaste, busy)
            else -> {
                Waiting("Waiting for ChatGPT… Sign in and allow Kinuvo to use your plan.")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space16)) {
                    LinkText("Open the page again", Lucide.ExternalLink) { runCatching { uri.openUri(signIn.url!!) } }
                    LinkText("Cancel", null, onCancel)
                }
            }
        }
        signIn?.error?.let { Text(it, color = color(destructiveColor), style = TextStyles.secondary) }
    }
    var other by remember { mutableStateOf(false) }
    GroupRow("Other ways to sign in", { other = !other }, (if (onApiKey != null) "A sign-in code, or an OpenAI API key" else "If the browser can't come back to Kinuvo").takeIf { !other }, Lucide.KeyRound, chevron = false) {
        Icon(if (other) Lucide.ChevronUp else Lucide.ChevronDown, Modifier.size(16.dp), tint = color(mutedColor))
    }
    AnimatedVisibility(other) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (signIn?.url != null && !pasteOpen) ActionRow("Paste the address the browser ended on", Lucide.ClipboardPaste, { pasting = true })
            ActionRow("Use a sign-in code instead", Lucide.Hash, { onCancel(); onCode() }, enabled = !busy,
                subtitle = "Enter a code on OpenAI's page from any device")
            onApiKey?.let { ActionRow("Use an OpenAI API key", Lucide.KeyRound, { onCancel(); it() }, enabled = !busy, subtitle = "Billed to your OpenAI API account, not your ChatGPT plan") }
        }
    }
}

/** OpenAI's Continue with ChatGPT button: black, with the ChatGPT logo. */
@Composable
internal fun ContinueWithChatGpt(onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true) {
    Row(modifier.height(44.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black)
        .clickable(enabled, role = Role.Button, onClick = onClick).padding(horizontal = Spacing.space20),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(ChatGptLogo, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.White)
        Spacer(Modifier.width(Spacing.space10))
        Text("Continue with ChatGPT", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
    }
}

/** Where the ChatGPT plan is in use: says so, with a way to its usage settings. */
@Composable
internal fun UsingChatGptPlan() {
    val uri = LocalUriHandler.current
    GroupBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space10)) {
            Icon(ChatGptLogo, Modifier.size(16.dp), tint = color(onBackgroundColor))
            Text("Using ChatGPT plan", Modifier.weight(1f), style = TextStyles.secondary, fontWeight = FontWeight.SemiBold)
            LinkText("Manage usage", null) { runCatching { uri.openUri(ChatGptUsageUrl) } }
        }
    }
}

/** Signed in, but plan usage wasn't allowed: asks again, or switches account. */
@Composable
internal fun ChatGptPlanBlocked(label: String?, onAllow: () -> Unit, onAnother: () -> Unit, button: Boolean = true) {
    GroupBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space10)) {
            Icon(ChatGptLogo, Modifier.size(20.dp), tint = color(onBackgroundColor))
            Text("ChatGPT plan use isn't allowed", style = TextStyles.label.copy(fontWeight = FontWeight.SemiBold))
        }
        Text("You're signed in${label?.let { " as $it" }.orEmpty()}, but Kinuvo can't use your plan until you allow it on ChatGPT's page.",
            color = color(mutedColor), style = TextStyles.secondary)
        if (button) Row(Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black)
            .clickable(role = Role.Button, onClick = onAllow), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(ChatGptLogo, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.White)
            Spacer(Modifier.width(Spacing.space10))
            Text("Allow ChatGPT plan usage", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
        }
        LinkText("Use another account", null, onAnother)
    }
}

/** Shown once, right after signing in with plan usage. */
@Composable
internal fun ChatGptPlanWelcome(onDismiss: () -> Unit) {
    GroupBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space10)) {
            Icon(ChatGptLogo, Modifier.size(20.dp), tint = color(onBackgroundColor))
            Text("You're using your ChatGPT plan", style = TextStyles.label.copy(fontWeight = FontWeight.SemiBold))
        }
        Text("Eligible requests in Kinuvo now use your ChatGPT plan. You can manage usage in ChatGPT settings.", color = color(mutedColor), style = TextStyles.secondary)
        Button(onClick = onDismiss, style = ButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
    }
}

@Composable
private fun PasteCallback(onPaste: (String) -> Unit, busy: Boolean) {
    val field = rememberTextFieldState()
    Text("Sign in on the ChatGPT page. It ends on a page that won't load; that's expected. Copy that page's address and paste it here.",
        color = color(mutedColor), style = TextStyles.secondary)
    Input(field, "http://127.0.0.1:1455/auth/callback?code=…")
    Button(onClick = { onPaste(field.text.toString().trim()) }, enabled = field.text.isNotBlank() && !busy, style = ButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) {
        Text("Finish sign-in")
    }
}

@Composable
private fun Waiting(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space10)) {
    Spinner(); Text(text, Modifier.weight(1f), color = color(mutedColor), style = TextStyles.secondary)
}

@Composable
private fun LinkText(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) =
    Row(Modifier.clip(RoundedCornerShape(Radius.small)).clickable(role = Role.Button, onClick = onClick).padding(vertical = Spacing.space4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        icon?.let { Icon(it, Modifier.size(14.dp)) }
        Text(text, style = TextStyles.secondary.copy(textDecoration = TextDecoration.Underline), fontWeight = FontWeight.SemiBold)
    }
