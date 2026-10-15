package kinuvo.ui.agent

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.app.navigation.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.ui.components.*
import com.composables.icons.lucide.*
import com.composables.ui.theme.mutedColor
import kinuvo.protocol.AgentDetails
import kinuvo.protocol.ContainerSettings
import kinuvo.protocol.ExecTestResult
import kinuvo.protocol.Integration
import kinuvo.protocol.offers
import kinuvo.protocol.SshSettings
import kinuvo.protocol.TestExecParams
import kinuvo.protocol.CreateAgentParams
import kinuvo.protocol.DenyRules
import kinuvo.protocol.TrustRules
import kinuvo.protocol.UpdateAgentParams
import kinuvo.ui.design.*

/**
 * One part of the Agent's settings: its Personality (name, Identity, Soul and model), its Workspace (where it runs) or
 * its Tools (built-in tools, MCP servers and Approvals). Each saves only what it shows.
 */
@Composable
internal fun AgentPane(p: Pane, section: SettingsSection) {
    val details = p.ui.config.details ?: return PaneLoading()
    key(details) { AgentForm(details, p, section) }
}

@Composable
private fun AgentForm(d: AgentDetails, p: Pane, section: SettingsSection) {
    val s = d.settings
    val name = rememberTextFieldState(d.info.name)
    val model = rememberTextFieldState(s.model)
    var provider by remember { mutableStateOf(s.provider) }
    val catalog = p.ui.state.models
    val group = catalog.providers.firstOrNull { it.id == provider }
    val selected = catalog.model(provider, model.value())
    var reasoning by remember { mutableStateOf(s.reasoning) }
    val validReasoning = selected?.let {
        val levels = it.reasoning
        if (levels == null || reasoning in levels) reasoning else it.reasoningFor(reasoning)
    } ?: reasoning
    var approvals by remember { mutableStateOf(s.approvals) }
    var exec by remember { mutableStateOf(s.exec) }
    val workspace = rememberTextFieldState(s.workspace.joinToString("\n"))
    val image = rememberTextFieldState(s.container.image)
    var network by remember { mutableStateOf(s.container.network) }
    val host = rememberTextFieldState(s.ssh?.host.orEmpty())
    val user = rememberTextFieldState(s.ssh?.user.orEmpty())
    val port = rememberTextFieldState(s.ssh?.port?.toString().orEmpty())
    val keyFile = rememberTextFieldState(s.ssh?.identityFile.orEmpty())
    val command = rememberTextFieldState()
    val container = ContainerSettings(image.value().ifEmpty { ContainerSettings().image }, network)
    val ssh = host.value().takeIf { it.isNotEmpty() }?.let {
        SshSettings(it, user.value().ifEmpty { null }, port.value().toIntOrNull(), keyFile.value().ifEmpty { null })
    }
    var tools by remember { mutableStateOf(s.tools) }
    val identity = rememberTextFieldState(d.identity)
    val soul = rememberTextFieldState(d.soul)
    val trusted = rememberTextFieldState(d.rules.trusted.joinToString("\n"))
    val shell = rememberTextFieldState(d.rules.shell.joinToString("\n"))
    val denyTools = rememberTextFieldState(d.rules.deny.tools.joinToString("\n"))
    val denyShell = rememberTextFieldState(d.rules.deny.shell.joinToString("\n"))
    val rules = TrustRules(trusted.lines(), shell.lines(), DenyRules(denyTools.lines(), denyShell.lines()))
    val changes = UpdateAgentParams(
        d.info.id,
        name = name.value().takeIf { it.isNotEmpty() && it != d.info.name },
        model = model.value().takeIf { it.isNotEmpty() && (it != s.model || provider != s.provider) },
        provider = provider.takeIf { it != s.provider },
        reasoning = validReasoning.takeIf { it != s.reasoning },
        approvals = approvals.takeIf { it != s.approvals },
        exec = exec.takeIf { it != s.exec },
        workspace = workspace.lines().takeIf { it != s.workspace },
        container = container.takeIf { it != s.container },
        ssh = ssh.takeIf { it != s.ssh },
        tools = tools.takeIf { it.toSet() != s.tools.toSet() },
        identity = identity.text.toString().takeIf { it != d.identity },
        soul = soul.text.toString().takeIf { it != d.soul },
        rules = rules.takeIf { it != d.rules },
    )
    val dirty = changes != UpdateAgentParams(d.info.id)
    DetailPane(p.back, section.title, error = p.ui.error, trailing = { FormActions("Save", dirty, p.ui.state.saved && !dirty, onSave = { p.actions.onSaveAgent(changes) }) }) {
        when (section) {
            SettingsSection.Workspace -> {
                DetailGroup("Shell and files") {
                    val (offered, missing) = EXEC.keys.partition { it == "none" || p.ui.capabilities.offers(Integration.Exec, it) }
                    DetailRow("Runs on") { Picker((offered + s.exec).distinct(), exec, { EXEC[it] ?: it }, { exec = it }) }
                    if (missing.isNotEmpty()) Text("Not available on this Server: ${missing.joinToString { EXEC.getValue(it).lowercase() }}.", color = color(mutedColor))
                    // Older Servers ignore these settings and have no test; they're edited in agent.yaml there.
                    val legacy = p.ui.capabilities?.integrations == null
                    if (legacy && exec in setOf("container", "ssh")) Text("Update the Server to set up containers and SSH here; until then, edit agent.yaml.", color = color(mutedColor))
                    if (!legacy) when (exec) {
                        "container" -> {
                            FormField("Image", "Pulled on first use; needs Docker or Podman on the Server.") { Input(image, ContainerSettings().image) }
                            Checkbox(network, { network = it }) { Text("Network access") }
                        }
                        "ssh" -> {
                            FormField("Host") { Input(host, "build.local") }
                            FormField("User") { Input(user, "Same as the Server's") }
                            FormField("Port") { Input(port, "22") }
                            FormField("Key file", "A private key on the Server; blank uses its SSH config and agent.") { Input(keyFile, "~/.ssh/id_ed25519") }
                        }
                    }
                    val remote = if (exec == "ssh") "on the remote machine" else "on the Server"
                    if (exec != "none") FormField("Workspace", "Folders $remote the agent's file tools may touch, one per line; commands start in the first.") {
                        TextArea(workspace, "~/projects", lines = 2, capitalize = false)
                    }
                }
                if (exec != "none" && p.ui.capabilities?.integrations != null) DetailGroup("Test") {
                    FormField("Command", "Optional; runs in the first Workspace folder with these unsaved settings.") { Input(command, "git status") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        ActionButton(Lucide.PlugZap, "Test connection", {
                            p.actions.onTestExec(TestExecParams(d.info.id, exec, workspace.lines(), container, ssh, command.value().ifEmpty { null }))
                        }, enabled = !p.ui.state.testing)
                        if (p.ui.state.testing) Spinner()
                    }
                    p.ui.state.execTest?.let { t -> ExecTestResultView(t) }
                }
            }
            SettingsSection.Tools -> {
                DetailGroup("Built-in tools") {
                    TOOLS.forEach { (tool, label) ->
                        if (p.ui.capabilities.offers(Integration.Tools, tool)) Checkbox(tool in tools, { on -> tools = if (on) tools + tool else tools - tool }) { Text(label) }
                        else Text("$label · not available on this Server", color = color(mutedColor))
                    }
                }
                DetailGroup("MCP servers") {
                    Text(p.ui.config.mcp?.count { it.active }?.let { "$it active" } ?: "…", color = color(mutedColor))
                    ActionButton(Lucide.Plug, "Manage MCP servers", { p.go(p.ui.route.copy(section = SettingsSection.Mcp, item = null, editing = false)) },
                        enabled = p.ui.available(SettingsSection.Mcp))
                }
                DetailGroup("Approvals") {
                    DetailRow("Ask before") { Picker(APPROVALS.keys.toList(), approvals, { APPROVALS[it] ?: it }, { approvals = it }) }
                    FormField("Always allowed tools", "Full tool keys, one per line; use Always allow on an Approval to save the correct key and scope.") { TextArea(trusted, "tool:kinuvo.core/default/memory", lines = 2, capitalize = false) }
                    FormField("Always allowed commands", "Commands that run with any further arguments, one per line. High-risk ones must match exactly.") {
                        TextArea(shell, "git push\n./kotlin test", lines = 3, capitalize = false)
                    }
                    FormField("Blocked tools", "Never run: tool names (all instances) or full tool keys, one per line.") { TextArea(denyTools, "skill_manage", lines = 2, capitalize = false) }
                    FormField("Blocked commands", "Patterns with * and ?, matched against each command and the whole line.") {
                        TextArea(denyShell, "git push --force*", lines = 3, capitalize = false)
                    }
                }
            }
            else -> {
                FormField("Name", "The title of IDENTITY.md.") { Input(name, "Kinuvo", capitalize = true) }
                FormField("Identity (IDENTITY.md)", "Who the agent is: name and personality.") { TextArea(identity, lines = 4) }
                FormField("Soul (SOUL.md)", "The principles, tone and rules it follows.") { TextArea(soul, lines = 6) }
                DetailGroup("Model") {
                    DetailRow("Provider") {
                        Picker((catalog.providers.map { it.id } + s.provider).distinct(), provider,
                            { id -> catalog.providers.firstOrNull { it.id == id }?.name ?: id }, { id ->
                                provider = id
                                catalog.providers.firstOrNull { it.id == id }?.models?.firstOrNull()?.let {
                                    model.setTextAndPlaceCursorAtEnd(it.id)
                                    reasoning = it.reasoningFor(reasoning)
                                }
                            })
                    }
                    if (!group?.models.isNullOrEmpty()) DetailRow("Model") {
                        Picker(group.models.map { it.id } + listOf(model.value()).filter { it.isNotEmpty() && it !in group.models.map { m -> m.id } }, model.value(),
                            { id -> group.models.firstOrNull { it.id == id }?.name ?: id }, { id ->
                                model.setTextAndPlaceCursorAtEnd(id)
                                group.models.firstOrNull { it.id == id }?.let { reasoning = it.reasoningFor(reasoning) }
                            })
                    }
                    DetailRow("Model ID") { Input(model, "Custom model ID") }
                    val levels = selected?.reasoning ?: (REASONING + s.reasoning).distinct()
                    DetailRow("Reasoning") {
                        if (levels.isEmpty()) Text("Not supported", color = color(mutedColor))
                        else Picker(levels, validReasoning, { it }, { reasoning = it })
                    }
                    group?.warning?.let { Text(it, color = color(mutedColor)) }
                    catalog.warning?.let { Text(it, color = color(mutedColor)) }
                    ActionButton(com.composables.icons.lucide.Lucide.RefreshCw, "Refresh models", p.actions.onRefreshModels)
                }
                if (p.ui.agents.size > 1) DetailGroup("Delete") {
                    Text("Deletes the agent with its sessions, Memory, Skills and Routines.", color = color(mutedColor))
                    DeleteButton("agent", d.info.id, p.actions.onDeleteAgent)
                }
            }
        }
    }
}

@Composable
private fun ExecTestResultView(t: ExecTestResult) {
    if (t.error != null) Pill("Couldn't connect: ${t.error}", tone = Tone.Danger, icon = Lucide.CircleX)
    else Pill("Works: ${t.backend}", tone = Tone.Success, icon = Lucide.CircleCheck)
    if (t.error == null) CodeBlock(t.files.joinToString("\n").ifEmpty { "(empty folder)" }, maxLines = 12)
    if (t.command != null) {
        Text("$ ${t.command}  (exit ${t.exitCode})", color = color(mutedColor))
        CodeBlock(t.output.orEmpty().ifEmpty { "(no output)" }, maxLines = 20)
    }
}

/** A new Agent from the template, or a copy of another one's settings, Identity, Soul, Skills and MCP servers. */
@Composable
internal fun NewAgentForm(p: Pane) {
    val id = rememberTextFieldState()
    val name = rememberTextFieldState()
    var from by remember { mutableStateOf<String?>(null) }
    val save = { p.actions.onCreateAgent(CreateAgentParams(id.value(), name.value().ifEmpty { null }, from)) }
    DetailPane(p.back, "New agent", error = p.ui.error, trailing = { FormActions("Create agent", id.value().isNotEmpty(), p.ui.state.saved, onSave = save, onCancel = p.cancel) }) {
        FormField("ID", "Lowercase letters, digits and hyphens; names its folder.") { Input(id, "work") }
        FormField("Name") { Input(name, "Work", capitalize = true) }
        FormField("Start from") {
            Picker(listOf<String?>(null) + p.ui.agents.map { it.id }, from, { a -> a?.let { "Copy of ${p.ui.agents.first { it.id == a }.name}" } ?: "Default template" }, { from = it })
        }
    }
}

internal val REASONING = listOf("minimal", "low", "medium", "high", "xhigh")
private val TOOLS = listOf("memory" to "Memory", "web_fetch" to "Web (fetch, search)", "skills" to "Skills", "schedule" to "Routines", "knowledge" to "Knowledge", "tasks" to "Tasks", "ask_user" to "Ask the user (forms)", "artifact" to "Artifacts", "device_tools" to "Device tools (Contacts, workouts, sleep)", "run_code" to "Scripts (chain tool calls in JavaScript)", "computer" to "Computer use (see and control a screen)")
internal val APPROVALS = mapOf(
    "strict" to "Every tool call",
    "default" to "Anything that changes things",
    "relaxed" to "Only risky calls (deleting, sudo…)",
    "full" to "Never ask",
)
internal val EXEC = mapOf("none" to "Nowhere (no shell)", "host" to "This server machine", "container" to "A container", "ssh" to "A machine over SSH")
