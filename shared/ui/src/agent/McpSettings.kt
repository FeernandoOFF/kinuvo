package kinuvo.ui.agent

import kinuvo.ui.session.HeaderButton
import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.McpAuthStatus
import kinuvo.protocol.McpOAuthClient
import kinuvo.protocol.McpServerInfo
import kinuvo.protocol.Integration
import kinuvo.protocol.IntegrationOptions
import kinuvo.protocol.offers
import kinuvo.ui.design.*

@Composable
internal fun McpList(p: Pane) {
    val servers = p.ui.config.mcp
    ItemList(
        p, "MCP servers",
        "Tools from other programs and services. ${p.ui.agent?.name ?: "The agent"} connects on its next run; " +
            "its own servers win over shared ones of the same name.",
        "New MCP server", servers == null,
    ) {
        if (!servers.isNullOrEmpty()) RowGroup { servers.forEach { s ->
            ItemRow(itemKey(s.scope, s.name) == p.ui.route.item, Lucide.Plug, s.name, s.target(), { p.go(p.ui.route.copy(item = itemKey(s.scope, s.name), editing = false)) }) {
                if (s.command != null && !p.ui.capabilities.offers(Integration.Mcp, IntegrationOptions.MCP_STDIO)) Pill("Needs a desktop Server", tone = Tone.Warning)
                if (s.auth == McpAuthStatus.Required) Pill("Needs sign-in", tone = Tone.Warning)
                if (!s.active) Pill("Overridden")
                if (s.agents?.contains(p.ui.route.agentId) == false) Pill("Off for this agent")
                if (s.scope != ConfigScope.Agent) Pill(s.scope.label())
            }
        } }
        if (servers != null) McpCatalogGroup(servers, p)
    }
}

/** Popular servers to add in a few taps; added ones open. */
@Composable
internal fun McpCatalogGroup(servers: List<McpServerInfo>, p: Pane) {
    if (!p.ui.capabilities.offers(Integration.Mcp, IntegrationOptions.MCP_HTTP)) return
    RowGroup("Catalog") {
        McpCatalog.forEach { preset ->
            val added = servers.firstOrNull { it.name == preset.id && it.url == preset.url }
            if (added == null) ItemRow(presetItem(preset) == p.ui.route.item, Lucide.Plus, preset.name, preset.description,
                { p.go(p.ui.route.copy(item = presetItem(preset), editing = true)) })
            else ItemRow(itemKey(added.scope, added.name) == p.ui.route.item, Lucide.Check, preset.name, "Added · ${preset.description}",
                { p.go(p.ui.route.copy(item = itemKey(added.scope, added.name), editing = false)) })
        }
    }
}

@Composable
internal fun McpPane(p: Pane) {
    val route = p.ui.route
    val server = parseItem(route.item)?.let { (scope, name) -> p.ui.config.mcp?.firstOrNull { it.scope == scope && it.name == name } }
    when {
        route.editing && route.item == null -> McpForm(null, p)
        presetOf(route.item) != null -> McpForm(null, p, presetOf(route.item))
        server == null -> if (p.ui.config.mcp == null) PaneLoading() else DetailPane(p.back, "Not found") {}
        route.editing -> McpForm(server, p)
        else -> McpDetail(server, p)
    }
}

@Composable
private fun McpDetail(s: McpServerInfo, p: Pane) {
    val agent = p.ui.agent?.name ?: "this agent"
    DetailPane(p.back, s.name, info = s.target(), trailing = { HeaderButton(Lucide.Pencil, "Edit", { p.go(p.ui.route.copy(editing = true)) }) }) {
        p.ui.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        p.ui.state.signInUrl?.let { SignInWaiting(it, p) }
        RowGroup {
            if (s.scope == ConfigScope.Shared) {
                val everyAgent = p.ui.agents.map { it.id }
                GroupRow("Use with $agent", subtitle = "Shared; each Agent can turn it on or off", chevron = false) {
                    Switch(s.agents?.contains(p.ui.route.agentId) != false, { p.actions.onMcpAccess(s.name, p.ui.route.agentId, it, everyAgent) })
                }
            } else GroupRow("Only $agent uses it", subtitle = if (s.active) null else "Overridden", chevron = false)
            GroupRow("Approvals", value = APPROVAL[s.approval].orEmpty(), chevron = false)
            ActionRow(if (p.ui.state.testing) "Connecting…" else "Reconnect", Lucide.RefreshCw, { p.actions.onTestMcp(s.scope, s.name) }, enabled = !p.ui.state.testing)
            if (s.url != null) {
                if (s.auth == McpAuthStatus.SignedIn) ConfirmRow("Sign out", Lucide.LogOut, "Sign out of ${s.name}?", { p.actions.onSignOutMcp(s.scope, s.name) },
                    detail = "Its tools stop working until you sign in again.")
                else ActionRow("Sign in", Lucide.LogIn, { p.actions.onSignInMcp(s.scope, s.name) }, enabled = p.ui.state.signInUrl == null,
                    subtitle = AUTH.getValue(s.auth))
            }
        }
        McpToolRules(s, p)
        DetailGroup("Connection") {
            if (s.command != null) {
                DetailRow("Command") { Text((listOf(s.command!!) + s.args).joinToString(" "), style = MonoStyle) }
                s.env.forEach { (k, v) -> DetailRow(k) { Text(v, style = MonoStyle) } }
            } else {
                DetailRow("URL") { Text(s.url.orEmpty(), style = MonoStyle) }
                s.headers.forEach { (k, v) -> DetailRow(k) { Text(v, style = MonoStyle) } }
                s.oauth?.let { o ->
                    DetailRow("OAuth client") { Text(o.clientId, style = MonoStyle) }
                    if (o.scopes.isNotEmpty()) DetailRow("Scopes") { Text(o.scopes.joinToString(" "), style = MonoStyle) }
                }
            }
            s.includeTools?.let { DetailRow("Offered") { Text(it.joinToString(", ")) } }
            if (s.excludeTools.isNotEmpty()) DetailRow("Hidden") { Text(s.excludeTools.joinToString(", ")) }
        }
        RowGroup {
            ConfirmRow("Delete server", Lucide.Trash2, "Delete ${s.name}?", { p.actions.onRemoveMcp(s.scope, s.name) }, "Delete",
                detail = if (s.scope == ConfigScope.Shared) "Every Agent loses its tools." else "$agent loses its tools.")
        }
    }
}

/** A sign-in open in the browser; its URL can be pasted when the browser can't come back here. */
@Composable
private fun SignInWaiting(url: String, p: Pane) {
    val pasted = rememberTextFieldState()
    DetailGroup("Signing in") {
        Text("Finish signing in in your browser. If it doesn't open, open this link:", fontSize = 13.sp)
        SelectionContainer { Text(url, style = MonoStyle, maxLines = 3) }
        Text("If the browser can't get back to Kinuvo, paste the address it ends on:", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Input(pasted, "Paste the address", Modifier.weight(1f))
            ActionButton(Lucide.Check, "Finish", { p.actions.onCompleteMcpSignIn(pasted.value()) }, enabled = pasted.value().isNotEmpty())
            ActionButton(Lucide.X, "Cancel", p.actions.onCancelMcpSignIn)
        }
    }
}

/** A new server when [s] is null, prefilled from [preset] if given; otherwise edits it. */
@Composable
private fun McpForm(s: McpServerInfo?, p: Pane, preset: McpPreset? = null) {
    val draft = s ?: preset?.server()
    val name = rememberTextFieldState(draft?.name.orEmpty())
    var scope by remember { mutableStateOf(s?.scope ?: ConfigScope.Shared) }
    val stdio = p.ui.capabilities.offers(Integration.Mcp, IntegrationOptions.MCP_STDIO)
    var http by remember { mutableStateOf(draft?.url != null || s == null && !stdio) }
    val command = rememberTextFieldState(draft?.command.orEmpty())
    val args = rememberTextFieldState(s?.args.orEmpty().joinToString("\n"))
    val env = rememberTextFieldState(s?.env.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" })
    val url = rememberTextFieldState(draft?.url.orEmpty())
    val headers = rememberTextFieldState(draft?.headers.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" })
    val include = rememberTextFieldState(s?.includeTools?.joinToString(", ").orEmpty())
    val exclude = rememberTextFieldState(s?.excludeTools.orEmpty().joinToString(", "))
    var approval by remember { mutableStateOf(s?.approval) }
    val clientId = rememberTextFieldState(draft?.oauth?.clientId.orEmpty())
    val clientSecret = rememberTextFieldState(s?.oauth?.clientSecret.orEmpty())
    val scopes = rememberTextFieldState(draft?.oauth?.scopes.orEmpty().joinToString(" "))
    val taken = s == null && p.ui.config.mcp.orEmpty().any { it.name == name.value() && it.scope == scope }
    val ready = name.value().isNotEmpty() && !taken && (if (http) url else command).value().isNotEmpty() && when (preset?.auth) {
        McpPresetAuth.Token -> headers.value().substringAfter("Bearer", "").isNotBlank()
        McpPresetAuth.OwnClient -> clientId.value().isNotEmpty()
        else -> true
    }
    val save = {
        fun pairs(lines: List<String>) = lines.mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        p.actions.onSaveMcp(McpServerInfo(
            name = s?.name ?: name.value(),
            scope = scope,
            command = command.value().takeIf { !http },
            args = if (http) emptyList() else args.lines(),
            env = if (http) emptyMap() else pairs(env.lines()),
            url = url.value().takeIf { http },
            headers = if (http) pairs(headers.lines()) else emptyMap(),
            includeTools = include.commaList().takeIf { it.isNotEmpty() },
            excludeTools = exclude.commaList(),
            approval = approval,
            agents = s?.agents.takeIf { scope == ConfigScope.Shared },
            oauth = clientId.value().takeIf { http && it.isNotEmpty() }?.let { id ->
                McpOAuthClient(id, clientSecret.value().ifEmpty { null }, scopes.value().split(" ").filter { it.isNotBlank() })
            },
        ))
    }
    DetailPane(p.back, s?.let { "Edit ${it.name}" } ?: preset?.let { "Add ${it.name}" } ?: "New MCP server", error = p.ui.error, trailing = { FormActions(if (s == null) "Add server" else "Save", ready, p.ui.state.saved, onSave = save, onCancel = p.cancel.takeIf { preset == null }, dirty = preset != null || formChanged(listOf(name.text.toString(), scope, http, command.text.toString(), args.text.toString(), env.text.toString(), url.text.toString(), headers.text.toString(), include.text.toString(), exclude.text.toString(), approval, clientId.text.toString(), clientSecret.text.toString(), scopes.text.toString()), p.ui.state.saved)) }) {
        preset?.let { PresetHelp(it) }
        if (s == null) RowGroup {
            FieldRow("Name", "Its tools are named <name>__<tool>.") { Input(name, "github") }
            if (taken) GroupRow("A server named ${name.value()} already exists here", subtitle = "Choose another name.", tint = color(destructiveColor))
            PickerRow("For", listOf(ConfigScope.Agent, ConfigScope.Shared), scope, { if (it == ConfigScope.Shared) "Shared" else "Only ${p.ui.agent?.name ?: "this agent"}" }, { scope = it }, Lucide.Users)
        }
        // A catalog server only asks for what its sign-in needs.
        val auth = preset?.auth
        val connection = preset == null || http && (auth == null || auth == McpPresetAuth.Token)
        if (connection) RowGroup("Connection") {
            if (preset == null && (stdio || s?.command != null)) PickerRow("Connect with", listOf(false, true), http, { if (it) "HTTP" else "A command (stdio)" }, { http = it },
                if (http) Lucide.Globe else Lucide.SquareTerminal)
            if (preset == null && !stdio) GroupRow("Over HTTP", subtitle = "Local (stdio) servers need a desktop Server.", icon = Lucide.Globe)
            if (http) {
                if (preset == null) FieldRow("URL") { Input(url, "https://example.com/mcp") }
                if (auth == null || auth == McpPresetAuth.Token) FieldRow("Headers", REFERENCES) {
                    SecretField(p, { headers.insert(it) }) { TextArea(headers, "Authorization=Bearer \${TOKEN}", lines = 2, mono = true, trailing = it) }
                }
            } else {
                FieldRow("Command") { Input(command, "npx") }
                FieldRow("Arguments", "One per line.") { TextArea(args, "-y\n@modelcontextprotocol/server-filesystem", lines = 3, mono = true) }
                FieldRow("Environment", REFERENCES) {
                    SecretField(p, { env.insert(it) }) { TextArea(env, "GITHUB_TOKEN=\${GITHUB_TOKEN}", lines = 2, mono = true, trailing = it) }
                }
            }
        }
        if (http && (auth == null || auth == McpPresetAuth.OwnClient)) RowGroup("Sign-in") {
            FieldRow("OAuth client ID", "Only for servers whose sign-in needs a registered app; most register Kinuvo themselves.") {
                Input(clientId, if (auth == null) "Optional" else "Required")
            }
            if (clientId.value().isNotEmpty()) {
                FieldRow("OAuth client secret", REFERENCES) {
                    SecretField(p, { clientSecret.insert(it) }) { Input(clientSecret, "Optional", secret = true, trailing = it) }
                }
                FieldRow("OAuth scopes", "Space-separated; blank for the server's own.") { Input(scopes, "read write") }
            }
        }
        RowGroup("Tools") {
            PickerRow("Approvals", APPROVAL.keys.toList(), approval, { APPROVAL[it].orEmpty() }, { approval = it }, Lucide.ShieldCheck)
            FieldRow("Only these tools", "Comma-separated; blank for all.") { Input(include, "All of them") }
            FieldRow("Never these tools", "Comma-separated.") { Input(exclude, "None") }
        }
    }
}

@Composable
private fun PresetHelp(preset: McpPreset) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    RowGroup {
        GroupBox {
            Text(preset.description)
            Text(when (preset.auth) {
                McpPresetAuth.OAuth -> "After adding it, sign in with your ${preset.name} account."
                McpPresetAuth.Token -> "Put your token after \"Bearer\" in Headers, or a \${…} reference to it."
                McpPresetAuth.OwnClient -> "Create an OAuth client for it (see the setup guide) and enter its ID and secret; the scopes are filled in."
            }, fontSize = 13.sp, color = color(mutedColor))
        }
        preset.help?.let { ActionRow("Setup guide", Lucide.ExternalLink, { runCatching { uri.openUri(it) } }) }
    }
}

private val AUTH = mapOf(McpAuthStatus.None to "Not signed in", McpAuthStatus.Required to "Needs sign-in", McpAuthStatus.SignedIn to "Signed in")

internal val APPROVAL = mapOf(null to "By read-only hint", "auto" to "Never ask", "ask" to "Always ask")

internal fun McpServerInfo.target() = command?.let { (listOf(it) + args).joinToString(" ") } ?: url.orEmpty()
