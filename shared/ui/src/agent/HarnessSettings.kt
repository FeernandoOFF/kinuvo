package kinuvo.ui.agent

import kinuvo.app.agent.*
import kinuvo.app.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun ReadinessTone.ui() = when (this) {
    ReadinessTone.Ready -> Tone.Success
    ReadinessTone.Neutral -> Tone.Neutral
    ReadinessTone.Warning -> Tone.Warning
    ReadinessTone.Danger -> Tone.Danger
}

@Composable
internal fun ReadinessPill(r: Readiness, checking: Boolean = false) =
    Pill(if (checking) "Checking…" else r.pill, tone = if (checking) Tone.Neutral else r.tone.ui(), loading = checking)

/** A harness's pill and what's wrong, with the actions that can fix it here. */
@Composable
internal fun ReadinessNote(r: Readiness, checking: Boolean, onAction: (ReadinessAction) -> Unit, actions: List<ReadinessAction> = r.actions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadinessPill(r, checking)
        r.message?.let { Text(it, fontSize = 13.sp, color = color(mutedColor)) }
        if (actions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { a ->
                when (a) {
                    ReadinessAction.CheckAgain -> ActionButton(Lucide.RefreshCw, "Check again", { onAction(a) }, enabled = !checking)
                    ReadinessAction.Edit -> ActionButton(Lucide.Pencil, "Edit", { onAction(a) })
                    ReadinessAction.Remove -> ActionButton(Lucide.Trash2, "Remove Agent", { onAction(a) }, destructive = true)
                }
            }
        }
    }
}

/** Server › Harnesses: Kinuvo itself, then each ACP command and linked Server, opening their details and forms. */
@Composable
internal fun HarnessesPane(p: Pane) {
    val route = p.ui.route
    val h = p.ui.state.harnesses
    val status = h.connections?.firstOrNull { it.config.id == route.item }
    when {
        route.item == LinkItem -> LinkPane(p)
        route.item == ShareItem -> SharePane(p)
        route.editing -> p.ui.state.acp?.let { AcpFormPane(it, p) } ?: PaneLoading()
        route.item != null -> if (status != null) HarnessDetail(status, p) else if (h.catalog == null) PaneLoading() else DetailPane(p.back, "Not found") {}
        else -> HarnessList(p)
    }
}

private fun HarnessConnectionStatus.subtitle(server: String) =
    (if (kind == ExecutionKind.Acp) "ACP command" else "Kinuvo Server") + " · ${config.host ?: server}"

@Composable
private fun HarnessList(p: Pane) {
    val h = p.ui.state.harnesses
    val server = p.ui.serverName
    val acp = p.ui.capabilities.offers(Integration.Harness, "acp")
    val remote = p.ui.capabilities.offers(Integration.Harness, "remote")
    DetailPane(p.back, SettingsSection.Harnesses.title, error = p.ui.error,
        info = "A harness runs Agents. Kinuvo runs its own; an ACP command runs one you already use, like Claude Code or Codex, on $server.") {
        val catalog = h.catalog ?: return@DetailPane Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            ItemRow(false, Lucide.Sparkles, "Kinuvo", "Built in · $server", {}) {
                Text(agentCount(p.ui.agents.count { !it.external }), fontSize = 12.sp, color = color(mutedColor))
            }
            catalog.connections.forEach { c ->
                val r = readiness(c, server)
                ItemRow(false, if (c.kind == ExecutionKind.Acp) Lucide.SquareTerminal else Lucide.ArrowLeftRight, c.config.name, c.subtitle(server),
                    { p.go(p.ui.route.copy(item = c.config.id, editing = false)) }) {
                    Text(agentCount(catalog.agents.count { it.connectionId == c.config.id }), fontSize = 12.sp, color = color(mutedColor))
                    ReadinessPill(r, c.config.id in h.checking)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Lucide.Plus, "Add ACP command", { p.go(p.ui.route.copy(item = null, editing = true)) }, enabled = acp)
            ActionButton(Lucide.Link, "Link a Kinuvo Server", { p.go(p.ui.route.copy(item = LinkItem, editing = true)) }, enabled = remote)
            ActionButton(Lucide.Share2, "Share Agents", { p.go(p.ui.route.copy(item = ShareItem, editing = true)) }, enabled = remote)
        }
        if (!acp) Text("ACP commands need a computer Server; $server can't run them.", fontSize = 13.sp, color = color(mutedColor))
        if (!remote) Text("Linking Kinuvo Servers isn't available on $server.", fontSize = 13.sp, color = color(mutedColor))
    }
}

@Composable
private fun HarnessDetail(c: HarnessConnectionStatus, p: Pane) {
    val h = p.ui.state.harnesses
    val server = p.ui.serverName
    val agents = h.catalog?.agents.orEmpty().filter { it.connectionId == c.config.id }
    val r = readiness(c, server)
    DetailPane(p.back, c.config.name, error = p.ui.error) {
        DetailGroup("Status") {
            ReadinessNote(r, c.config.id in h.checking, { a ->
                when (a) {
                    ReadinessAction.CheckAgain -> p.actions.onCheckHarness(c.config.id)
                    ReadinessAction.Edit -> p.go(p.ui.route.copy(editing = true))
                    ReadinessAction.Remove -> Unit
                }
            }, r.actions.ifEmpty { listOf(ReadinessAction.CheckAgain) })
            h.probes[c.config.id]?.let { ProbeLines(it, c.config.name, c.config.host ?: server) }
        }
        DetailGroup("Runs") {
            DetailRow("On") { Text(c.config.host ?: server) }
            (c.config.connection as? ExecutionConnection.Acp)?.let { acp ->
                DetailRow("Command") { Text(acp.command.first(), style = MonoStyle) }
                acp.command.drop(1).forEachIndexed { i, arg -> DetailRow(if (i == 0) "Arguments" else "") { Text(arg, style = MonoStyle) } }
            }
            DetailRow("Agents") { Text(agents.joinToString { it.name }.ifEmpty { "None yet" }) }
        }
        Text(if (c.kind == ExecutionKind.Acp) "${c.config.name} keeps its own instructions, Memory, Skills, tools and sign-in on ${c.config.host ?: server}. Kinuvo starts it there, in each Agent's working folder."
            else "Its Agents run on ${c.config.name}, with that Server's settings. Add them from New Agent.", fontSize = 13.sp, color = color(mutedColor))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.kind == ExecutionKind.Acp) ActionButton(Lucide.Pencil, "Edit", { p.go(p.ui.route.copy(editing = true)) })
            if (agents.isEmpty()) DeleteButton("harness", c.config.id) { p.actions.onRemoveHarness(c.config.id) }
            else ActionButton(Lucide.Trash2, "Delete", {}, destructive = true, enabled = false)
        }
        if (agents.isNotEmpty()) Text("Used by ${agents.joinToString { it.name }}. Remove those Agents first.", fontSize = 13.sp, color = color(mutedColor))
    }
}

/** What a check found: the harness's name and version, and how it can be signed in to (on its own machine). */
@Composable
private fun ProbeLines(probe: ExecutionProbe, harness: String, host: String) {
    listOfNotNull(probe.title, probe.version).joinToString(" ").takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.Normal) }
    if (probe.authenticated == false) Text("$harness on $host isn't signed in.", fontSize = 13.sp, color = color(mutedColor))
    authMethods(probe).takeIf { it.isNotEmpty() }?.let { methods ->
        Text("Sign in with ${methods.joinToString(" or ")} in $harness on $host, then check again.", fontSize = 13.sp, color = color(mutedColor))
    }
}

/** The names of the probe's sign-in methods, as the harness reports them. */
private fun authMethods(probe: ExecutionProbe): List<String> = (probe.authMethods as? JsonArray).orEmpty().mapNotNull { m ->
    ((m as? JsonObject)?.get("name") as? JsonPrimitive)?.content
}

@Composable
private fun AcpFormPane(form: AcpForm, p: Pane) {
    val connections = p.ui.state.harnesses.connections.orEmpty()
    val server = p.ui.serverName
    val host = connections.firstOrNull { it.config.id == form.id }?.config?.host ?: server
    val key = form.id ?: "new"
    val problems = form.problems(connections)
    DetailPane(p.back, if (form.id == null) "Add ACP command" else "Edit ${form.name}", error = p.ui.error,
        trailing = { FormActions("Save", form.saveable(connections) && !form.checking, p.ui.state.saved, onSave = p.actions.onSaveAcp, onCancel = p.cancel) }) {
        FormField("Name") { Input(draftField(key, "name", form.name) { v -> p.actions.onEditAcp { it.copy(name = v) } }, "Claude Code", capitalize = true) }
        FormField("Command", "A command on $host's PATH, or an absolute path. Kinuvo runs it as is, never through a shell.") {
            Input(draftField(key, "command", form.command) { v -> p.actions.onEditAcp { it.copy(command = v) } }, "claude-code-acp", capitalize = false)
        }
        Text("Runs on $host. Use an ACP adapter already installed there, e.g. claude-code-acp.", fontSize = 13.sp, color = color(mutedColor))
        FormField("Arguments", "One per row, passed exactly as written.") {
            TextArea(draftField(key, "args", form.args.joinToString("\n")) { v -> p.actions.onEditAcp { it.copy(args = v.split("\n")) } }, "Optional", lines = 2, mono = true)
        }
        FormField("Environment", "Key/value pairs added to the command on $host. Values are stored on the selected Server.") {
            form.environment.forEachIndexed { index, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Input(draftField(key, "env-key-$index", row.first) { v -> p.actions.onEditAcp { f -> f.copy(environment = f.environment.toMutableList().apply { set(index, v to row.second) }) } }, "KEY", Modifier.weight(1f))
                    Input(draftField(key, "env-value-$index", row.second) { v -> p.actions.onEditAcp { f -> f.copy(environment = f.environment.toMutableList().apply { set(index, row.first to v) }) } }, "Value", Modifier.weight(1f))
                    ActionButton(Lucide.Trash2, "Remove variable", { p.actions.onEditAcp { f -> f.copy(environment = f.environment.filterIndexed { i, _ -> i != index }) } })
                }
            }
            ActionButton(Lucide.Plus, "Add variable", { p.actions.onEditAcp { it.copy(environment = it.environment + ("" to "")) } })
        }
        if (form.codex) Text("For a separate Codex account, set CODEX_HOME to an absolute folder on $host, e.g. /Users/you/.codex-work. Sign in with Codex using that folder on $host, then check again.", fontSize = 13.sp, color = color(mutedColor))
        problems.forEach { Text(it, fontSize = 12.sp, color = color(destructiveColor)) }
        val probe = form.probe?.takeIf { form.checked }
        Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (probe == null) Text(if (form.checking) "Checking on $host…" else "Check the command on $host before saving. Checking starts it and closes it; it doesn't start a Session.",
                    fontSize = 13.sp, color = color(mutedColor))
                else {
                    ProbeLines(probe, form.name.ifBlank { probe.title ?: "The harness" }, host)
                    val r = readiness(probe.readiness.state(), form.name.ifBlank { probe.title ?: "The harness" }, host, command = form.command.trim())
                    // The sign-in lines above already say what to do.
                    ReadinessNote(if (probe.authenticated == false) r.copy(message = null) else r, form.checking, {}, emptyList())
                }
                ActionButton(Lucide.PlugZap, if (probe == null) "Check" else "Check again", p.actions.onCheckAcp, enabled = problems.isEmpty() && !form.checking)
            }
        }
        if (probe != null && !form.saveable(connections) && problems.isEmpty()) Text("Fix what the check found before saving.", fontSize = 12.sp, color = color(mutedColor))
    }
}

/** Links another Kinuvo Server with a link code made there; its code stays on this Server and isn't shown again. */
@Composable
private fun LinkPane(p: Pane) {
    val form = p.ui.state.link
    val problems = form.problems(p.ui.state.harnesses.connections.orEmpty())
    DetailPane(p.back, "Link a Kinuvo Server", error = p.ui.error,
        trailing = { FormActions(if (form.linking) "Linking…" else "Link", problems.isEmpty() && !form.linking, false, onSave = p.actions.onLink, onCancel = p.cancel) }) {
        Text("On the other Server, open Settings › Harnesses › Share Agents, pick the Agents it shares with ${p.ui.serverName}, and copy its link code.",
            color = color(mutedColor), fontSize = 13.sp)
        FormField("Name", "What this Server calls it, e.g. Studio. Its Agents' rows say they run there.") {
            Input(draftField("link", "name", form.name) { v -> p.actions.onEditLink { it.copy(name = v) } }, "Studio", capitalize = true)
        }
        FormField("Address", "The other Server's https:// address.") {
            Input(draftField("link", "url", form.url) { v -> p.actions.onEditLink { it.copy(url = v) } }, "https://studio.example.com")
        }
        FormField("Link code") { Input(draftField("link", "token", form.token) { v -> p.actions.onEditLink { it.copy(token = v) } }, "From the other Server", secret = true) }
        if (form.url.isNotBlank() || form.name.isNotBlank()) problems.forEach { Text(it, fontSize = 12.sp, color = color(destructiveColor)) }
        Text("A link code only lets ${p.ui.serverName} run the Agents it names. It can't change the other Server's settings.", fontSize = 12.sp, color = color(mutedColor))
    }
}

/** Makes a link code another Server uses to run the picked Agents here, and nothing else. */
@Composable
private fun SharePane(p: Pane) {
    val form = p.ui.state.share
    val agents = shareable(p.ui.agents)
    LaunchedEffect(Unit) { p.actions.onLoadGrants() }
    DetailPane(p.back, "Share Agents", error = p.ui.error) {
        Text("Another Kinuvo Server can run the Agents you pick here, on ${p.ui.serverName}. It can't see or change anything else: no settings, other Agents or Sessions it didn't start.",
            color = color(mutedColor), fontSize = 13.sp)
        val token = form.token
        if (token == null) {
            FormField("For", "A name for this link code, e.g. the other Server's.") {
                Input(draftField("share", "name", form.name) { v -> p.actions.onEditShare { it.copy(name = v) } }, "Studio", capitalize = true)
            }
            DetailGroup("Agents it can run") {
                agents.forEach { a ->
                    Checkbox(a.id in form.agents, { on -> p.actions.onEditShare { it.copy(agents = if (on) it.agents + a.id else it.agents - a.id) } }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AgentAvatar(a, 20.dp)
                            Text(a.name)
                        }
                    }
                }
            }
            ActionButton(Lucide.KeyRound, "Make link code", p.actions.onShareAgents, enabled = form.problems().isEmpty())
        } else DetailGroup("Link code for ${form.name}") {
            Text("Paste it into Link a Kinuvo Server on the other Server, with ${p.ui.serverName}'s https:// address. It's shown only now.", fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(token, Modifier.weight(1f), style = MonoStyle, maxLines = 2)
                CopyButton(token)
            }
            Text("Shares ${agents.filter { it.id in form.agents }.joinToString { it.name }}.", fontSize = 13.sp, color = color(mutedColor))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(Lucide.RefreshCw, "Make a new code", p.actions.onShareAgents)
                ActionButton(Lucide.Ban, "Stop sharing", { form.id?.let(p.actions.onRevokeShare) }, destructive = true)
            }
            Text("A new code stops the old one working.", fontSize = 12.sp, color = color(mutedColor))
        }
        val grants = p.ui.state.grants
        if (grants == null) Spinner()
        else if (grants.isNotEmpty()) DetailGroup("Shared now") {
            grants.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(g.id)
                        Text(g.agentIds.map { id -> p.ui.agents.firstOrNull { it.id == id }?.name ?: id }.joinToString(), fontSize = 12.sp, color = color(mutedColor))
                    }
                    DeleteButton("link code", g.id) { p.actions.onRevokeShare(g.id) }
                }
            }
            Text("Deleting a link code stops the Server using it from running these Agents.", fontSize = 12.sp, color = color(mutedColor))
        }
    }
}

private fun agentCount(n: Int) = if (n == 1) "1 Agent" else "$n Agents"
