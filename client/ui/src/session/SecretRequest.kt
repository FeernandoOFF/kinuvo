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
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.FileKey
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.LockKeyhole
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Vault
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.session.ChatState
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.secret.SecretProviderStatus
import kinuvo.ui.design.*

/** A `request_secret` card: an item picked in a connected password manager, or a value typed here. Either way the Agent gets a reference, never the value. */
@Composable
internal fun SecretBody(asked: PendingInteraction.Secret, state: ChatState, input: RequestInput, wide: Boolean, actions: ChatActions) {
    LaunchedEffect(asked.id) { actions.onLoadSecretProviders() }
    val providers = state.secretProviders.orEmpty()
    // A password manager first: what's picked there outlives the Session.
    val modes = providers.map { it.name } + "Type it"
    val mode = input.step.coerceIn(0, modes.lastIndex)
    val provider = providers.getOrNull(mode)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
        if (modes.size > 1) Segmented(modes, mode, small = true) { input.step = it; input.picked = null }
        if (provider == null) Input(input.secret, asked.label, secret = true)
        else input.picked?.let { picked ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Icon(Lucide.KeyRound, Modifier.size(14.dp), tint = color(accentColor))
                Text((listOf(provider.name) + picked.drop(1)).joinToString(" / "), Modifier.weight(1f), style = TextStyles.secondary, singleLine = true, overflow = TextOverflow.Ellipsis)
                Button(onClick = { input.picked = null }, modifier = Modifier.heightIn(min = 44.dp), style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(accentColor)) {
                    Text("Change", style = TextStyles.caption)
                }
            }
        } ?: SecretPicker(provider, input.path?.takeIf { it.firstOrNull() == provider.scheme } ?: listOf(provider.scheme), state, actions,
            onPath = { input.path = it }, onPick = { input.picked = it })
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8), verticalAlignment = Alignment.Top) {
            Icon(Lucide.LockKeyhole, Modifier.size(13.dp).padding(top = Spacing.space2), tint = color(mutedColor))
            Text(
                if (provider == null) "The Agent gets a reference, never the value. The Server keeps what you type in memory for this Session only."
                else "The Agent gets a reference to this item. The Server reads it from ${provider.name} only when a tool uses it.",
                style = TextStyles.caption, color = color(mutedColor),
            )
        }
        val ready = if (provider == null) input.secret.text.isNotEmpty() else input.picked != null
        Actions(wide,
            primary = {
                Button(onClick = {
                    val picked = input.picked
                    if (provider == null) actions.onTypeSecret(asked.id, input.secret.text.toString()) else if (picked != null) actions.onPickSecret(asked.id, picked)
                }, modifier = it, enabled = ready, style = ButtonStyle.Primary) { Text("Share") }
            },
            others = { Button(onClick = { actions.onDeclineSecret(asked.id) }, modifier = it, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Text("Decline") } })
        if (providers.isEmpty() && state.secretProviders != null)
            Text("Connect a password manager in Settings → Secrets to pick credentials from it.", style = TextStyles.caption, color = color(mutedColor))
    }
}

/** Vaults, items and fields of [provider] (names only), down to the field whose path [onPick] gets. */
@Composable
private fun SecretPicker(provider: SecretProviderStatus, path: List<String>, state: ChatState, actions: ChatActions, onPath: (List<String>) -> Unit, onPick: (List<String>) -> Unit) {
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
                    ListRow(selected = false, onClick = { if (field) onPick(path + e.name) else onPath(path + e.name) },
                        leading = { Icon(if (field) Lucide.KeyRound else if (path.size == 1) Lucide.Vault else Lucide.FileKey, Modifier.size(14.dp)) }) {
                        Text(e.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                        if (e.kind.isNotEmpty()) Text(e.kind, style = TextStyles.caption, color = color(mutedColor))
                    }
                }
            }
        }
    }
}
