package kinuvo.ui.agent

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.connection.*
import kinuvo.ui.board.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.ProviderInfo
import kinuvo.ui.design.*

/** The Server's model providers: sign in to the ones that need an account, with a code entered in a browser. */
@Composable
internal fun ProvidersSettingsPane(p: Pane) {
    val providers = p.ui.state.providers ?: return ServerLoading(p, "Model providers")
    DetailPane(p.back, "Model providers", error = p.ui.error, info = "Models use the Server’s credentials. Configure its API key or sign in here, wherever the Server runs.") {
        providers.forEach { ProviderGroup(it, p) }
    }
}

/** One provider: its status first, then the one thing to do about it, e.g. sign in or add an API key. */
@Composable
private fun ProviderGroup(provider: ProviderInfo, p: Pane) {
    val account = provider.account
    val apiKey = provider.apiKey
    val code = p.ui.state.deviceCode?.takeIf { it.first == provider.id }?.second
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowGroup(provider.name) {
            when {
                apiKey != null -> GroupRow(if (apiKey.configured) "API key set" else "Add an API key", icon = Lucide.KeyRound,
                    subtitle = if (apiKey.configured) apiKey.reference ?: apiKey.storage else "Its models work once the Server has a key") {
                    if (apiKey.configured) Icon(Lucide.CircleCheck, Modifier.size(18.dp), tint = color(successColor))
                    else Pill("Needed", tone = Tone.Warning)
                }
                account == null -> GroupRow("Ready", subtitle = "No sign-in needed", icon = Lucide.CircleCheck)
                account.signedIn -> GroupRow(account.label ?: "Your account", subtitle = "Signed in", icon = Lucide.CircleUserRound) {
                    Icon(Lucide.CircleCheck, Modifier.size(18.dp), tint = color(successColor))
                }
                code != null -> GroupBox {
                    Text("Enter this code on the sign-in page", color = color(mutedColor), fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(code.code, Modifier.weight(1f), style = MonoStyle, fontSize = 22.sp)
                        CopyButton(code.code)
                    }
                    Text("It expires in 15 minutes.", color = color(mutedColor), fontSize = 12.sp)
                }
                else -> GroupRow("Not signed in", subtitle = "Sign in to use its models", icon = Lucide.CircleUserRound) { Pill("Signed out", tone = Tone.Warning) }
            }
            apiKey?.let { key ->
                val value = rememberTextFieldState()
                GroupBox {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            SecretField(p, { value.edit { replace(0, length, it) } }) { trailing ->
                                Input(value, if (key.configured) "New key or \${…} reference" else "API key or \${…} reference", secret = !isReference(value.value()), trailing = trailing)
                            }
                        }
                        Button({ p.actions.onSaveProviderApiKey(provider.id, value.value()); value.edit { replace(0, length, "") } }, enabled = value.value().isNotBlank(),
                            style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text("Save") }
                    }
                }
                if (key.saved) ConfirmRow("Remove saved key", Lucide.Trash2, "Remove the saved key for ${provider.name}?", { p.actions.onSaveProviderApiKey(provider.id, "") }, "Remove",
                    detail = "Its models stop working until you add another.")
            }
            if (account != null) when {
                code != null -> ActionRow("Open sign-in page", Lucide.ExternalLink, { runCatching { uri.openUri(code.url) } })
                account.signedIn -> ConfirmRow("Sign out", Lucide.LogOut, "Sign out of ${provider.name}?", { p.actions.onLogoutProvider(provider.id) },
                    detail = "Agents using its models stop working until you sign in again.")
                else -> ActionRow("Sign in", Lucide.LogIn, { p.actions.onLoginProvider(provider.id) })
            }
        }
        provider.error?.let { Text(it, Modifier.padding(horizontal = 6.dp), color = color(destructiveColor), fontSize = 12.sp) }
        if (apiKey != null) Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved keys stay on the Server. Refresh models after changing it.", Modifier.weight(1f), color = color(mutedColor), fontSize = 12.sp)
            if (p.ui.state.saved) Pill("Saved", tone = Tone.Success, icon = Lucide.CircleCheck)
        }
    }
}
