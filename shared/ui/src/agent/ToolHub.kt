package kinuvo.ui.agent

import kinuvo.app.agent.*
import kinuvo.app.navigation.*
import kinuvo.ui.connection.ServerLoading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.mutedColor
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.DenyRules
import kinuvo.protocol.Integration
import kinuvo.protocol.TrustRules
import kinuvo.protocol.offers
import kinuvo.ui.design.*

/** Every Agent's tools and shared MCP servers in one place: who can use each, and the rules every Agent follows. */
@Composable
internal fun ToolHubPane(p: Pane) {
    val rules = p.ui.state.serverRules ?: return ServerLoading(p, SettingsSection.Hub.title)
    val agents = p.ui.agents
    val configs = p.ui.state.configs
    val first = agents.firstOrNull()?.id ?: return ServerLoading(p, SettingsSection.Hub.title)
    DetailPane(p.back, SettingsSection.Hub.title, error = p.ui.error,
        info = "Set up a tool or MCP server once, then choose which Agents use it. Rules here apply to every Agent.") {
        if (p.ui.available(SettingsSection.Mcp)) DetailGroup("MCP servers") {
            val everyAgent = agents.map { it.id }
            val shared = p.ui.config.mcp?.filter { it.scope == ConfigScope.Shared }.orEmpty()
            if (shared.isEmpty()) Text("No shared MCP servers yet.", color = color(mutedColor))
            shared.forEach { server ->
                AccessRow(server.name, server.url ?: server.command.orEmpty(), agents.map { it.id to it.name }, server.agents ?: everyAgent) { id, on ->
                    p.actions.onMcpAccess(server.name, id, on, everyAgent)
                }
            }
            configs.forEach { (id, c) ->
                c.mcp?.filter { it.scope == ConfigScope.Agent }?.forEach { server ->
                    Text("${server.name} · only ${agents.firstOrNull { it.id == id }?.name ?: id}", color = color(mutedColor), fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(Lucide.Plus, "Add MCP server", { p.go(Route.Settings(first, SettingsSection.Mcp, editing = true)) })
                ActionButton(Lucide.Plug, "Manage", { p.go(Route.Settings(first, SettingsSection.Mcp)) })
            }
        }
        DetailGroup("Built-in tools") {
            TOOLS.forEach { (tool, label) ->
                if (!p.ui.capabilities.offers(Integration.Tools, tool)) Text("$label · not available on this Server", color = color(mutedColor))
                else AccessRow(label, null, agents.filter { configs[it.id]?.details != null }.map { it.id to it.name },
                    agents.map { it.id }.filter { tool in configs[it]?.details?.settings?.tools.orEmpty() }) { id, on -> p.actions.onToolAccess(id, tool, on) }
            }
        }
        ServerRules(rules, p)
    }
}

/** A tool or server with a checkbox per Agent. */
@Composable
private fun AccessRow(title: String, subtitle: String?, agents: List<Pair<String, String>>, using: List<String>, onToggle: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, singleLine = true, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = color(mutedColor), fontSize = 12.sp, singleLine = true, overflow = TextOverflow.Ellipsis) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            agents.forEach { (id, name) -> Checkbox(id in using, { onToggle(id, it) }) { Text(name, fontSize = 13.sp) } }
        }
    }
}

@Composable
private fun ServerRules(rules: TrustRules, p: Pane) {
    key(rules) {
        val trusted = rememberTextFieldState(rules.trusted.joinToString("\n"))
        val shell = rememberTextFieldState(rules.shell.joinToString("\n"))
        val denyTools = rememberTextFieldState(rules.deny.tools.joinToString("\n"))
        val denyShell = rememberTextFieldState(rules.deny.shell.joinToString("\n"))
        val edited = TrustRules(trusted.lines(), shell.lines(), DenyRules(denyTools.lines(), denyShell.lines()))
        DetailGroup("Rules for every Agent") {
            Text("Each Agent adds its own on top. A block here wins over any Agent's Always allow.", color = color(mutedColor), fontSize = 13.sp)
            FormField("Blocked tools", "Tool names (all instances) or full tool keys, one per line.") { TextArea(denyTools, "skill_manage", lines = 2, capitalize = false) }
            FormField("Blocked commands", "Patterns with * and ?, matched against each command and the whole line.") { TextArea(denyShell, "git push --force*", lines = 2, capitalize = false) }
            FormField("Always allowed tools", "Full tool keys, one per line.") { TextArea(trusted, "tool:kinuvo.core/default/memory", lines = 2, capitalize = false) }
            FormField("Always allowed commands", "Commands that run with any further arguments. High-risk ones must match exactly.") { TextArea(shell, "git status", lines = 2, capitalize = false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(Lucide.Save, "Save rules", { p.actions.onSaveServerRules(edited) }, enabled = edited != rules)
                if (p.ui.state.saved && edited == rules) Pill("Saved", tone = Tone.Success, icon = Lucide.CircleCheck)
            }
        }
    }
}
