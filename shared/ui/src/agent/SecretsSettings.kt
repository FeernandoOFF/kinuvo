package kinuvo.ui.agent

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

internal const val REFERENCES = "KEY=value per line; values can use \${VAR} from .env files or \${pass://Vault/Item/field} from Proton Pass."

private val REFERENCE = Regex("""\$\{[^}]+\}""")

/** Whether [value] is just a `${…}` reference, which isn't secret itself. */
internal fun isReference(value: String) = REFERENCE.matches(value.trim())

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
        if (s.installed) FieldRow(
            if (s.connected) "Replace access token" else "Access token",
            if (s.scheme == "pass") "In Proton Pass → Settings → Access tokens, create a token for an AI agent with only the vaults Kinuvo may read, " +
                "then paste the instructions it shows (or just the token in them). It's read-only, and Proton Pass logs every read with what it was for." else null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Input(token, "Paste the token or instructions", secret = true) }
                Button({ p.actions.onConnectSecrets(s.scheme, token.value()) }, enabled = token.value().isNotEmpty() && !p.ui.state.testing,
                    style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text(if (p.ui.state.testing) "Connecting…" else "Connect") }
            }
        }
        if (s.connected) ConfirmRow("Disconnect", Lucide.Unplug, "Disconnect ${s.name}?", { p.actions.onDisconnectSecrets(s.scheme) },
            detail = "Settings that refer to its secrets stop working until you connect again.")
    }
}

/**
 * A secret [field] with a key button inside it, given as its trailing content, when a password manager is connected: it
 * browses vaults, items and fields (names only) below the field and gives the picked field's reference to [onPick].
 */
@Composable
internal fun SecretField(p: Pane, onPick: (String) -> Unit, field: @Composable (trailing: (@Composable () -> Unit)?) -> Unit) {
    val providers = p.ui.state.secrets
    LaunchedEffect(providers == null) { if (providers == null) p.actions.onLoadSecrets() }
    val provider = providers?.firstOrNull { it.connected }
    var path by remember { mutableStateOf<List<String>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        field(provider?.let { { HeaderButton(Lucide.KeyRound, "From ${it.name}", { path = if (path == null) listOf(it.scheme) else null }) } })
        val open = path
        if (provider != null && open != null) SecretBrowser(p, provider, open, { path = it }, { onPick(it); path = null })
    }
}

@Composable
private fun SecretBrowser(p: Pane, provider: SecretProviderStatus, open: List<String>, onPath: (List<String>?) -> Unit, onPick: (String) -> Unit) {
    LaunchedEffect(open) { p.actions.onBrowseSecrets(open) }
    val entries = p.ui.state.secretEntries[open]
    Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(Lucide.ArrowLeft, "Back", { onPath(open.dropLast(1).takeIf { it.isNotEmpty() }) })
                Text(
                    (listOf(provider.name) + open.drop(1)).joinToString(" / "),
                    Modifier.weight(1f).padding(start = 4.dp), fontWeight = FontWeight.Normal, fontSize = 13.sp, singleLine = true, overflow = TextOverflow.Ellipsis,
                )
                HeaderButton(Lucide.X, "Close", { onPath(null) })
            }
            when {
                entries == null -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner() }
                entries.isEmpty() -> Text("Nothing here", Modifier.padding(10.dp), color = color(mutedColor), fontSize = 13.sp)
                else -> Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    entries.forEach { e ->
                        val field = open.size == 3
                        ListRow(
                            selected = false,
                            onClick = {
                                if (field) onPick(secretReference(provider.scheme, open.drop(1) + e.name))
                                else onPath(open + e.name)
                            },
                            leading = { Icon(if (field) Lucide.KeyRound else if (open.size == 1) Lucide.Vault else Lucide.FileKey, Modifier.size(14.dp)) },
                        ) {
                            Text(e.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                            if (e.kind.isNotEmpty()) Text(e.kind, fontSize = 12.sp, color = color(mutedColor))
                        }
                    }
                }
            }
        }
    }
}
