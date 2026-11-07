package kinuvo.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.session.ChatState
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SecretProviderStatus
import kinuvo.protocol.secretPath
import kinuvo.protocol.secretReference
import kinuvo.ui.design.*

/**
 * A `request_secret` card: the value typed here or an item picked in a connected password manager. Either way the
 * Agent gets a reference, never the value.
 */
@Composable
internal fun SecretRequestBody(asked: AgentEvent.SecretRequested, state: ChatState, input: RequestInput, wide: Boolean, actions: ChatActions) {
    LaunchedEffect(asked.requestId) { actions.onLoadSecretProviders() }
    val providers = state.secretProviders.orEmpty()
    // A password manager first: what's picked there outlives the Session.
    val modes = providers.map { it.name } + "Type it"
    val mode = input.step.coerceIn(0, modes.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
        if (modes.size > 1) Segmented(modes, mode, small = true) { input.step = it; input.reference = null }
        val provider = providers.getOrNull(mode)
        if (provider == null) Input(input.secret, asked.label, secret = true)
        else {
            val picked = input.reference
            if (picked != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Icon(Lucide.KeyRound, Modifier.size(14.dp), tint = color(accentColor))
                Text(secretPath(picked.removePrefix("\${").removeSuffix("}")).joinToString(" / "), Modifier.weight(1f), style = TextStyles.secondary, singleLine = true, overflow = TextOverflow.Ellipsis)
                Button(onClick = { input.reference = null }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(accentColor)) { Text("Change", style = TextStyles.caption) }
            }
            else SecretPicker(provider, input.path?.takeIf { it.firstOrNull() == provider.scheme } ?: listOf(provider.scheme), state, actions,
                onPath = { input.path = it }, onPick = { input.reference = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8), verticalAlignment = Alignment.Top) {
            Icon(Lucide.LockKeyhole, Modifier.size(13.dp).padding(top = Spacing.space2), tint = color(mutedColor))
            Text(
                if (provider == null) "The Agent gets a reference, never the value. Kinuvo keeps what you type in the Server's memory for this Session only."
                else "The Agent gets a reference to this item. The Server reads it from ${provider.name} only when a tool uses it.",
                style = TextStyles.caption, color = color(mutedColor),
            )
        }
        val ready = if (provider == null) input.secret.text.isNotEmpty() else input.reference != null
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Button(onClick = {
                if (provider == null) actions.onSecret(null, input.secret.text.toString()) else actions.onSecret(input.reference, null)
            }, modifier = Modifier.weight(1f), enabled = ready, style = ButtonStyle.Primary) { Text("Share") }
            Button(onClick = { actions.onSecret(null, null) }, modifier = Modifier.weight(1f), style = ButtonStyle.Outlined) { Text("Decline") }
        }
        if (providers.isEmpty() && state.secretProviders != null && wide)
            Text("Connect a password manager in Settings → Secrets to pick credentials from it.", style = TextStyles.caption, color = color(mutedColor))
    }
}

/** Vaults, items and fields of [provider] (names only), down to the field whose reference [onPick] gets. */
@Composable
private fun SecretPicker(provider: SecretProviderStatus, path: List<String>, state: ChatState, actions: ChatActions, onPath: (List<String>) -> Unit, onPick: (String) -> Unit) {
    LaunchedEffect(path) { actions.onBrowseSecrets(path) }
    val entries = state.secretEntries[path]
    Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.space6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (path.size > 1) HeaderButton(Lucide.ArrowLeft, "Back", { onPath(path.dropLast(1)) })
            Text((listOf(provider.name) + path.drop(1)).joinToString(" / "), Modifier.weight(1f).padding(horizontal = Spacing.space6),
                style = TextStyles.secondary, fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
        }
        when {
            entries == null -> Box(Modifier.fillMaxWidth().padding(Spacing.space12), contentAlignment = Alignment.Center) { Spinner() }
            entries.isEmpty() -> Text("Nothing here", Modifier.padding(Spacing.space10), color = color(mutedColor), style = TextStyles.secondary)
            else -> Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                val field = path.size == provider.levels
                entries.forEach { e ->
                    ListRow(selected = false, onClick = { if (field) onPick(secretReference(provider.scheme, path.drop(1) + e.name)) else onPath(path + e.name) },
                        leading = { Icon(if (field) Lucide.KeyRound else if (path.size == 1) Lucide.Vault else Lucide.FileKey, Modifier.size(14.dp)) }) {
                        Text(e.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                        if (e.kind.isNotEmpty()) Text(e.kind, style = TextStyles.caption, color = color(mutedColor))
                    }
                }
            }
        }
    }
}
