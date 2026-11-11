package kinuvo.ui.settings

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.connection.*
import kinuvo.ui.board.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import kinuvo.protocol.model.ProviderDownloads
import kinuvo.protocol.model.ProviderInfo
import kinuvo.protocol.secret.isReference
import kinuvo.ui.design.*

/** The Server's model providers: sign in to the ones that need an account, with a code entered in a browser. */
@Composable
internal fun ProvidersSettingsPane(p: Pane) {
    val providers = p.ui.state.providers ?: return ServerLoading(p, "Model providers")
    DetailPane(p.back, "Model providers", error = p.ui.error, info = "Models use the Server’s credentials. Configure its API key or sign in here, wherever the Server runs.") {
        providers.forEach { ProviderGroup(it, p) }
        var adding by remember { mutableStateOf(false) }
        RowGroup { ActionRow("Add OpenAI-compatible endpoint", Lucide.Plus, { adding = !adding }) }
        if (adding) EndpointForm { _, _ -> adding = false; p.actions.onRefreshModels() }
    }
}

/** One provider: its status first, then the one thing to do about it, e.g. sign in or add an API key. */
@Composable
private fun ProviderGroup(provider: ProviderInfo, p: Pane) {
    val account = provider.account
    val apiKey = provider.apiKey
    val code = p.ui.state.deviceCode?.takeIf { it.first == provider.id }?.second
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        RowGroup(provider.name) {
            when {
                apiKey != null -> GroupRow("API key", icon = Lucide.KeyRound,
                    subtitle = if (apiKey.configured) apiKey.reference ?: apiKey.storage else "Needs a key on the Server") {
                    if (apiKey.configured) Icon(Lucide.CircleCheck, Modifier.size(18.dp), tint = color(successColor))
                    else Pill("Needed", tone = Tone.Warning)
                }
                provider.downloads != null -> ModelDownloadRows(provider.downloads!!, { p.actions.onDownloadModel(provider.id, it) },
                    { p.actions.onCancelModelDownload(provider.id) }, { p.actions.onRemoveModel(provider.id, it) })
                account == null -> GroupRow(provider.name, subtitle = "No sign-in needed", icon = Lucide.CircleCheck) { Pill("Ready", tone = Tone.Success) }
                account.signedIn -> GroupRow(account.label ?: "Your account", subtitle = "Signed in", icon = Lucide.CircleUserRound) { Pill("Ready", tone = Tone.Success) }
                code != null -> GroupBox {
                    Text("Enter this code on the sign-in page", color = color(mutedColor), style = TextStyles.secondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(code.code, Modifier.weight(1f), style = MonoStyle, fontSize = TextStyles.headline.fontSize)
                        CopyButton(code.code)
                    }
                    Text("It expires in 15 minutes.", color = color(mutedColor), style = TextStyles.caption)
                }
                else -> GroupRow("Not signed in", subtitle = "Sign in to use its models", icon = Lucide.CircleUserRound) { Pill("Signed out", tone = Tone.Warning) }
            }
            apiKey?.let { key ->
                val value = rememberTextFieldState()
                GroupBox {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                        Box(Modifier.weight(1f)) {
                            SecretField(p, { value.edit { replace(0, length, it) } }) { trailing ->
                                Input(value, if (key.configured) "New key or \${…} reference" else "API key or \${…} reference", secret = !isReference(value.value()), trailing = trailing)
                            }
                        }
                        SaveButton({ p.actions.onSaveProviderApiKey(provider.id, value.value()); value.edit { replace(0, length, "") } }, enabled = value.value().isNotBlank())
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
        (provider.error ?: provider.downloads?.error)?.let { Text(it, Modifier.padding(horizontal = Spacing.space16), color = color(destructiveColor), style = TextStyles.caption) }
        if (provider.downloads != null) Text("Models run on the Server’s machine, without sending conversations anywhere. Downloads are large; use Wi‑Fi.",
            Modifier.padding(horizontal = Spacing.space16), color = color(mutedColor), style = TextStyles.caption)
        if (apiKey != null) Row(Modifier.padding(horizontal = Spacing.space16), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Column(Modifier.weight(1f)) {
                Text("Saved keys stay on the Server. Refresh models after changing a key.", color = color(mutedColor), style = TextStyles.caption)
                Text("Refresh models", Modifier.clickable(role = Role.Button, onClick = p.actions.onRefreshModels).padding(vertical = Spacing.space12), color = color(accentColor), style = TextStyles.secondary)
            }
            if (p.ui.state.saved) Pill("Saved", tone = Tone.Success, icon = Lucide.CircleCheck)
        }
    }
}

/** A provider's downloadable models: download one at a time with its progress, or remove one; ones this machine can't run say why. */
@Composable
internal fun ModelDownloadRows(downloads: ProviderDownloads, onDownload: (String) -> Unit, onCancel: () -> Unit, onRemove: (String) -> Unit) {
    downloads.models.forEach { model ->
        val size = gigabytes(model.bytes)
        when {
            downloads.downloading == model.id -> GroupBox {
                val progress = (downloads.downloaded.toFloat() / model.bytes.coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
                        Text(model.name)
                        Text("Downloading… ${gigabytes(downloads.downloaded)} of $size", color = color(mutedColor), style = TextStyles.secondary)
                    }
                    Button(onCancel, buttonSize = ButtonSize.Small, style = ButtonStyle.Outlined) { Text("Cancel") }
                }
                ProgressBar(progress)
            }
            !model.supported -> GroupRow(model.name, subtitle = model.reason, icon = Lucide.Cpu, enabled = false)
            model.installed -> {
                var asking by remember { mutableStateOf(false) }
                GroupRow(model.name, subtitle = "Downloaded · $size", icon = Lucide.CircleCheck) {
                    Button({ asking = true }, buttonSize = ButtonSize.Small, style = ButtonStyle.Outlined) { Text("Remove") }
                }
                ConfirmPopover(asking, "Remove ${model.name}?", "Agents using it stop working until you download it again ($size).", "Remove", true,
                    { asking = false }) { asking = false; onRemove(model.id) }
            }
            else -> GroupRow(model.name, subtitle = "$size · needs ${gigabytes(model.memory)} of RAM", icon = Lucide.Cpu) {
                Button({ onDownload(model.id) }, enabled = downloads.downloading == null, buttonSize = ButtonSize.Small, style = ButtonStyle.Outlined) { Text("Download") }
            }
        }
    }
}

internal fun gigabytes(bytes: Long) = if (bytes >= 1_000_000_000) "${(bytes / 100_000_000) / 10.0} GB" else "${bytes / 1_000_000} MB"

@Composable
internal fun ImageGenerationSettingsPane(p: Pane) {
    val state = p.ui.state
    DetailPane(p.back, "Image generation", error = p.ui.error,
        info = "Uses the Session’s model when it can generate images, then this fallback.") {
        RowGroup("Image model") {
            if (state.imageModels.isEmpty()) GroupRow("No image-capable model", subtitle = "Connect a model provider that can generate images.")
            else PickerRow("Fallback model", listOf(null) + state.imageModels, state.imageModel,
                { it?.let { "${it.provider} / ${it.model}" } ?: "Automatic" + state.automaticImageModel?.let { " (${it.model})" }.orEmpty() }, p.actions.onSaveImageModel)
        }
    }
}
