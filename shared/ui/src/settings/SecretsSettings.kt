package kinuvo.ui.settings

import kinuvo.app.connection.*
import kinuvo.ui.connection.*
import kinuvo.ui.session.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.SecretProviderStatus
import kinuvo.protocol.secretReference
import kinuvo.ui.design.*

internal const val REFERENCES = "KEY=value per line; values can use \${VAR} from .env files or a password manager reference such as \${pass://Vault/Item/field}."


/** Puts [text] where the cursor is, replacing any selection. */
internal fun TextFieldState.insert(text: String) = edit { replace(selection.min, selection.max, text) }

/** The Server's password managers: connect one with an access token, so settings can refer to its secrets. */
@Composable
internal fun SecretsSettingsPane(p: Pane) {
    val providers = p.ui.state.secrets ?: return ServerLoading(p, "Secrets")
    val info = "Instead of pasting a key into a setting, write a reference to your password manager, e.g. \${pass://Kinuvo/GitHub/token}, " +
        "in an Anytype API key, an MCP server's environment or headers, or an agent's exec environment. The Server reads it when it's " +
        "needed and keeps it in memory for a few minutes; it's never saved or shown. Refresh after changing a secret."
    DetailPane(p.back, "Secrets", error = p.ui.error, info = info, trailing = { HeaderButton(Lucide.RefreshCw, "Refresh secrets", p.actions.onRefreshSecrets) }) {
        providers.forEach { ProviderGroup(it, p) }
        if (p.ui.state.saved) Pill("Done", tone = Tone.Success, icon = Lucide.CircleCheck)
    }
}

@Composable
private fun ProviderGroup(s: SecretProviderStatus, p: Pane) {
    val token = rememberTextFieldState()
    RowGroup(s.name) {
        when {
            !s.installed -> GroupRow("Not installed", subtitle = s.detail, icon = Lucide.CircleAlert) { Pill("Missing", tone = Tone.Warning) }
            s.connected -> GroupRow("Connected", subtitle = s.detail, icon = Lucide.Vault) { Icon(Lucide.CircleCheck, Modifier.size(18.dp), tint = color(successColor)) }
            else -> GroupRow("Not connected", subtitle = s.detail, icon = Lucide.Vault)
        }
        GroupRow("References", value = "\${${s.scheme}://…}", chevron = false)
        if (s.tokenStorage.isNotEmpty()) GroupRow("Token kept in", value = s.tokenStorage, chevron = false)
        if (s.installed) FieldRow(if (s.connected) "Replace access token" else "Access token", tokenHelp(s.scheme)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Box(Modifier.weight(1f)) { Input(token, "Paste the token or instructions", secret = true) }
                Button({ p.actions.onConnectSecrets(s.scheme, token.value()) }, enabled = token.value().isNotEmpty() && !p.ui.state.testing,
                    style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text(if (p.ui.state.testing) "Connecting…" else "Connect") }
            }
        }
        if (s.connected) ConfirmRow("Disconnect", Lucide.Unplug, "Disconnect ${s.name}?", { p.actions.onDisconnectSecrets(s.scheme) },
            detail = "Settings that refer to its secrets stop working until you connect again.")
    }
}

private fun tokenHelp(scheme: String) = when (scheme) {
    "pass" -> "In Proton Pass → Settings → Access tokens, create a token for an AI agent with only the vaults Kinuvo may read, " +
        "then paste the instructions it shows (or just the token in them). It's read-only, and Proton Pass logs every read with what it was for."
    "op" -> "In 1Password → Developer → Service accounts, create a read-only service account with only the vaults Kinuvo may read " +
        "(not your Personal vault, which service accounts can't reach), then paste its token. References read op://Vault/Item/field."
    "bws" -> "In Bitwarden Secrets Manager → Machine accounts, create one with read access to only the projects Kinuvo may read, " +
        "then paste an access token from it. References read bws://Project/KEY."
    else -> null
}

/**
 * A secret [field] with a key button inside it per connected password manager, given as its trailing content: it browses
 * vaults, items and fields (names only) below the field and gives the picked field's reference to [onPick].
 */
@Composable
internal fun SecretField(p: Pane, onPick: (String) -> Unit, field: @Composable (trailing: (@Composable () -> Unit)?) -> Unit) {
    val providers = p.ui.state.secrets
    LaunchedEffect(providers == null) { if (providers == null) p.actions.onLoadSecrets() }
    val connected = providers.orEmpty().filter { it.connected }
    var path by remember { mutableStateOf<List<String>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        field(connected.takeIf { it.isNotEmpty() }?.let { all ->
            {
                Row {
                    all.forEach { FieldButton(Lucide.KeyRound, "From ${it.name}", { path = if (path?.firstOrNull() == it.scheme) null else listOf(it.scheme) }) }
                }
            }
        })
        val open = path
        val provider = connected.firstOrNull { it.scheme == open?.firstOrNull() }
        if (provider != null && open != null) SecretBrowser(p, provider, open, { path = it }, { onPick(it); path = null })
    }
}

@Composable
private fun SecretBrowser(p: Pane, provider: SecretProviderStatus, open: List<String>, onPath: (List<String>?) -> Unit, onPick: (String) -> Unit) {
    LaunchedEffect(open) { p.actions.onBrowseSecrets(open) }
    val entries = p.ui.state.secretEntries[open]
    Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.space6)) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(Lucide.ArrowLeft, "Back", { onPath(open.dropLast(1).takeIf { it.isNotEmpty() }) })
                Text(
                    (listOf(provider.name) + open.drop(1)).joinToString(" / "),
                    Modifier.weight(1f).padding(start = Spacing.space4), fontWeight = FontWeight.Normal, style = TextStyles.secondary, singleLine = true, overflow = TextOverflow.Ellipsis,
                )
                HeaderButton(Lucide.X, "Close", { onPath(null) })
            }
            when {
                entries == null -> Box(Modifier.fillMaxWidth().padding(Spacing.space12), contentAlignment = Alignment.Center) { Spinner() }
                entries.isEmpty() -> Text("Nothing here", Modifier.padding(Spacing.space10), color = color(mutedColor), style = TextStyles.secondary)
                else -> Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    entries.forEach { e ->
                        val field = open.size == provider.levels
                        ListRow(
                            selected = false,
                            onClick = {
                                if (field) onPick(secretReference(provider.scheme, open.drop(1) + e.name))
                                else onPath(open + e.name)
                            },
                            leading = { Icon(if (field) Lucide.KeyRound else if (open.size == 1) Lucide.Vault else Lucide.FileKey, Modifier.size(14.dp)) },
                        ) {
                            Text(e.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                            if (e.kind.isNotEmpty()) Text(e.kind, style = TextStyles.caption, color = color(mutedColor))
                        }
                    }
                }
            }
        }
    }
}
