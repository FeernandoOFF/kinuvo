package kinuvo.ui.agent

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.connection.*
import kinuvo.ui.board.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.mutedColor
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

@Composable
private fun ProviderGroup(provider: ProviderInfo, p: Pane) {
    val account = provider.account
    val apiKey = provider.apiKey
    val code = p.ui.state.deviceCode?.takeIf { it.first == provider.id }?.second
    DetailGroup(provider.name) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                apiKey != null -> if (apiKey.configured) Pill("API key configured", tone = Tone.Success, icon = Lucide.KeyRound)
                    else Pill("API key required", tone = Tone.Warning, icon = Lucide.KeyRound)
                account == null -> Pill("No sign-in needed", icon = Lucide.CircleCheck)
                account.signedIn -> Pill("Signed in", tone = Tone.Success, icon = Lucide.CircleCheck)
                code != null -> Pill("Waiting for sign-in", loading = true)
                else -> Pill("Signed out", tone = Tone.Warning, icon = Lucide.CircleAlert)
            }
            account?.label?.let { Text(it, color = color(mutedColor), fontSize = 13.sp) }
        }
        provider.error?.let { Text(it, color = color(mutedColor), fontSize = 12.sp) }
    }
    apiKey?.let { key ->
        val value = rememberTextFieldState()
        key.reference?.let { DetailRow("Secret reference") { Text(it, style = MonoStyle, fontSize = 12.sp) } }
        if (key.configured) DetailRow("Credential source") { Text(key.storage, fontSize = 13.sp) }
        FormField(if (key.configured) "Replace API key" else "API key", "Paste a key or choose a Secret reference. Saved keys stay on the Server.") {
            SecretField(p, { value.edit { replace(0, length, it) } }) { trailing ->
                Input(value, "API key or \${…} reference", secret = !isReference(value.value()), trailing = trailing)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Lucide.Save, "Save key", { p.actions.onSaveProviderApiKey(provider.id, value.value()); value.edit { replace(0, length, "") } }, enabled = value.value().isNotBlank())
            if (key.saved) ActionButton(Lucide.Trash2, "Remove saved key", { p.actions.onSaveProviderApiKey(provider.id, "") }, destructive = true)
        }
        if (p.ui.state.saved) Pill("Saved", tone = Tone.Success, icon = Lucide.CircleCheck)
        Text("Refresh models after changing the key.", color = color(mutedColor), fontSize = 12.sp)
    }
    if (account == null) return
    if (code != null) {
        val uri = LocalUriHandler.current
        DetailRow("Code") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(code.code, style = MonoStyle, fontSize = 18.sp)
                CopyButton(code.code)
            }
        }
        Text("Open the sign-in page and enter this code. It expires in 15 minutes.", color = color(mutedColor), fontSize = 12.sp)
        ActionButton(Lucide.ExternalLink, "Open sign-in page", { runCatching { uri.openUri(code.url) } })
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (account.signedIn) ActionButton(Lucide.LogOut, "Sign out", { p.actions.onLogoutProvider(provider.id) }, destructive = true)
        else ActionButton(Lucide.LogIn, "Sign in", { p.actions.onLoginProvider(provider.id) })
    }
}
