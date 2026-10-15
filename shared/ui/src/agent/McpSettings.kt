package kinuvo.ui.agent

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
import com.composables.ui.theme.mutedColor
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
        items(servers.orEmpty(), key = { itemKey(it.scope, it.name) }) { s ->
            ItemRow(itemKey(s.scope, s.name) == p.ui.route.item, Lucide.Plug, s.name, s.target(), { p.go(p.ui.route.copy(item = itemKey(s.scope, s.name), editing = false)) }) {
                if (s.command != null && !p.ui.capabilities.offers(Integration.Mcp, IntegrationOptions.MCP_STDIO)) Pill("Needs a desktop Server", tone = Tone.Warning)
                if (s.auth == McpAuthStatus.Required) Pill("Needs sign-in", tone = Tone.Warning)
                if (!s.active) Pill("Overridden")
                if (s.agents?.contains(p.ui.route.agentId) == false) Pill("Off for this agent")
                if (s.scope != ConfigScope.Agent) Pill(s.scope.label())
            }
        }
        if (servers != null && p.ui.capabilities.offers(Integration.Mcp, IntegrationOptions.MCP_HTTP)) {
            item { SectionLabel("Catalog", Modifier.padding(start = 10.dp, top = 12.dp)) }
            items(McpCatalog, key = { presetItem(it) }) { preset ->
                val added = servers.firstOrNull { it.name == preset.id && it.url == preset.url }
                if (added == null) ItemRow(presetItem(preset) == p.ui.route.item, Lucide.Plus, preset.name, preset.description,
                    { p.go(p.ui.route.copy(item = presetItem(preset), editing = true)) })
                else ItemRow(itemKey(added.scope, added.name) == p.ui.route.item, Lucide.Check, preset.name, preset.description,
                    { p.go(p.ui.route.copy(item = itemKey(added.scope, added.name), editing = false)) }) {
                    DeleteButton("server", itemKey(added.scope, added.name)) { p.actions.onRemoveMcp(added.scope, added.name) }
                }
            }
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
    val test = p.ui.state.mcpTest
    DetailPane(p.back, s.name) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Lucide.PlugZap, "Test", { p.actions.onTestMcp(s.scope, s.name) }, enabled = !p.ui.state.testing)
            if (s.url != null) {
                if (s.auth == McpAuthStatus.SignedIn) ActionButton(Lucide.LogOut, "Sign out", { p.actions.onSignOutMcp(s.scope, s.name) })
                else ActionButton(Lucide.LogIn, "Sign in", { p.actions.onSignInMcp(s.scope, s.name) }, enabled = p.ui.state.signInUrl == null)
            }
            ActionButton(Lucide.Pencil, "Edit", { p.go(p.ui.route.copy(editing = true)) })
            DeleteButton("server", itemKey(s.scope, s.name)) { p.actions.onRemoveMcp(s.scope, s.name) }
            if (p.ui.state.testing) Spinner()
        }
        p.ui.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        p.ui.state.signInUrl?.let { SignInWaiting(it, p) }
        test?.let { t ->
            if (t.needsLogin) Pill("Needs sign-in before it can connect", tone = Tone.Warning, icon = Lucide.LogIn)
            else if (t.error != null) Pill("Couldn't connect: ${t.error}", tone = Tone.Danger, icon = Lucide.CircleX)
            else DetailGroup("Tools (${t.tools.size})") { Text(t.tools.joinToString(", ").ifEmpty { "None" }) }
        }
        DetailGroup("Connection") {
            DetailRow("Where") { Text((if (s.scope == ConfigScope.Shared) "Shared with every agent" else "This agent") + if (s.active) "" else " · overridden") }
            if (s.command != null) {
                DetailRow("Command") { Text((listOf(s.command!!) + s.args).joinToString(" "), style = MonoStyle) }
                s.env.forEach { (k, v) -> DetailRow(k) { Text(v, style = MonoStyle) } }
            } else {
                DetailRow("URL") { Text(s.url.orEmpty(), style = MonoStyle) }
                s.headers.forEach { (k, v) -> DetailRow(k) { Text(v, style = MonoStyle) } }
                DetailRow("Sign-in") { Text(AUTH.getValue(s.auth)) }
                s.oauth?.let { o ->
                    DetailRow("OAuth client") { Text(o.clientId, style = MonoStyle) }
                    if (o.scopes.isNotEmpty()) DetailRow("Scopes") { Text(o.scopes.joinToString(" "), style = MonoStyle) }
                }
            }
        }
        DetailGroup("Tools") {
            DetailRow("Offered") { Text(s.includeTools?.joinToString(", ") ?: "All") }
            if (s.excludeTools.isNotEmpty()) DetailRow("Hidden") { Text(s.excludeTools.joinToString(", ")) }
            DetailRow("Approvals") { Text(APPROVAL[s.approval].orEmpty()) }
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
    DetailPane(p.back, s?.let { "Edit ${it.name}" } ?: preset?.let { "Add ${it.name}" } ?: "New MCP server", error = p.ui.error, trailing = { FormActions(if (s == null) "Add server" else "Save", ready, p.ui.state.saved, onSave = save, onCancel = p.cancel.takeIf { preset == null }) }) {
        preset?.let { PresetHelp(it) }
        if (s == null) {
            FormField("Name", "Its tools are named <name>__<tool>.") { Input(name, "github") }
            if (taken) Text("A server named ${name.value()} already exists here; choose another name.", color = color(mutedColor))
            FormField("For") { Picker(listOf(ConfigScope.Agent, ConfigScope.Shared), scope, { if (it == ConfigScope.Shared) "Agents chosen in Tools & MCP" else "Only this agent" }, { scope = it }) }
        }
        // A catalog server only asks for what its sign-in needs.
        val auth = preset?.auth
        if (preset == null && (stdio || s?.command != null)) FormField("Connect with") { Picker(listOf(false, true), http, { if (it) "HTTP (streamable)" else "A command (stdio)" }, { http = it }) }
        if (preset == null && !stdio) Text("Local (stdio) servers need a desktop Server; this one connects over HTTP.", color = color(mutedColor))
        if (http) {
            if (preset == null) FormField("URL") { Input(url, "https://example.com/mcp") }
            if (auth == null || auth == McpPresetAuth.Token) FormField("Headers", REFERENCES) {
                SecretField(p, { headers.insert(it) }) { TextArea(headers, "Authorization=Bearer \${TOKEN}", lines = 2, mono = true, trailing = it) }
            }
            if (auth == null || auth == McpPresetAuth.OwnClient) FormField("OAuth client ID", "Only for servers whose sign-in needs a registered app; most register Kinuvo themselves.") {
                Input(clientId, if (auth == null) "Optional" else "Required")
            }
            if (clientId.value().isNotEmpty()) {
                FormField("OAuth client secret", REFERENCES) {
                    SecretField(p, { clientSecret.insert(it) }) { Input(clientSecret, "Optional", secret = true, trailing = it) }
                }
                FormField("OAuth scopes", "Space-separated; blank for the server's own.") { Input(scopes, "read write") }
            }
        } else {
            FormField("Command") { Input(command, "npx") }
            FormField("Arguments", "One per line.") { TextArea(args, "-y\n@modelcontextprotocol/server-filesystem", lines = 3, mono = true) }
            FormField("Environment", REFERENCES) {
                SecretField(p, { env.insert(it) }) { TextArea(env, "GITHUB_TOKEN=\${GITHUB_TOKEN}", lines = 2, mono = true, trailing = it) }
            }
        }
        FormField("Only these tools", "Comma-separated; blank for all.") { Input(include, "search, read") }
        FormField("Never these tools", "Comma-separated.") { Input(exclude, "delete") }
        FormField("Approvals") { Picker(APPROVAL.keys.toList(), approval, { APPROVAL[it].orEmpty() }, { approval = it }) }
    }
}

@Composable
private fun PresetHelp(preset: McpPreset) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Text(when (preset.auth) {
        McpPresetAuth.OAuth -> "${preset.description} After adding it, sign in with your ${preset.name} account."
        McpPresetAuth.Token -> "${preset.description} Put your token after \"Bearer\" in Headers, or a \${…} reference to it."
        McpPresetAuth.OwnClient -> "${preset.description} Create an OAuth client for it (see the setup guide) and enter its ID and secret; the scopes are filled in."
    }, color = color(mutedColor))
    preset.help?.let { ActionButton(Lucide.ExternalLink, "Setup guide", { runCatching { uri.openUri(it) } }) }
}

private val AUTH = mapOf(McpAuthStatus.None to "Not signed in", McpAuthStatus.Required to "Needs sign-in", McpAuthStatus.SignedIn to "Signed in")

private val APPROVAL = mapOf(null to "By each tool's read-only hint", "auto" to "Never ask", "ask" to "Always ask")

private fun McpServerInfo.target() = command?.let { (listOf(it) + args).joinToString(" ") } ?: url.orEmpty()
