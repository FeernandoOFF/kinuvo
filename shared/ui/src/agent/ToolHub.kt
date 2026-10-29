package kinuvo.ui.agent

import kinuvo.app.agent.*
import kinuvo.app.navigation.*
import kinuvo.ui.connection.ServerLoading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.mutedColor
import kinuvo.protocol.*
import kinuvo.ui.design.*

/** The Tools page's own items besides MCP servers. */
internal const val BuiltInItem = "+builtin"
internal const val RulesItem = "+rules"
internal const val SharedRulesItem = "+shared-rules"

/** One Agent's tools, like its Routines: built-in tools, each MCP server, and its rules. */
@Composable
internal fun ToolList(p: Pane) {
    val ui = p.ui
    val details = ui.config.details
    val servers = ui.config.mcp
    val open = { item: String -> p.go(ui.route.copy(item = item, editing = false)) }
    ItemList(p, SettingsSection.Hub.title, "What ${ui.agent?.name ?: "the agent"} can use, and whether each tool runs, asks you first or is blocked.",
        "Add MCP server", details == null && servers == null) {
        RowGroup {
            val offered = TOOLS.filter { ui.capabilities.offers(Integration.Tools, it.first) }
            GroupRow("Built-in tools", { open(BuiltInItem) }, icon = Lucide.Wrench, selected = ui.route.item == BuiltInItem,
                subtitle = details?.settings?.tools?.let { on -> "${offered.count { it.first in on }} of ${offered.size} on" })
        }
        if (ui.available(SettingsSection.Mcp)) {
            RowGroup("MCP servers") {
                servers.orEmpty().filter { it.active }.forEach { s ->
                    ItemRow(itemKey(s.scope, s.name) == ui.route.item, Lucide.Plug, s.name, s.target(), { open(itemKey(s.scope, s.name)) }) {
                        if (s.agents?.contains(ui.route.agentId) == false) Pill("Off")
                        else if (s.auth == McpAuthStatus.Required) Pill("Needs sign-in", tone = Tone.Warning)
                    }
                }
                GroupRow("Add MCP server", { p.go(ui.route.copy(item = null, editing = true)) }, icon = Lucide.Plus, selected = ui.route.editing && ui.route.item == null)
            }
            servers?.let { McpCatalogGroup(it, p) }
        }
        RowGroup("Permissions") {
            GroupRow("Risk tolerance and rules", { open(RulesItem) }, icon = Lucide.ShieldCheck, selected = ui.route.item == RulesItem,
                subtitle = details?.let { "Ask before: ${APPROVALS[it.settings.approvals]?.lowercase() ?: it.settings.approvals}" + it.rules.counts() })
            GroupRow("Rules for every Agent", { open(SharedRulesItem) }, icon = Lucide.Users, selected = ui.route.item == SharedRulesItem,
                subtitle = ui.state.serverRules?.let { "Their blocks win over any Agent's" + it.counts() })
        }
    }
}

private fun TrustRules.counts() = listOfNotNull(
    (trusted.size + shell.size).takeIf { it > 0 }?.let { " · $it allowed" },
    (deny.tools.size + deny.shell.size).takeIf { it > 0 }?.let { " · $it blocked" },
).joinToString("")

/** The open set of tools, an MCP server's form, or the rules. */
@Composable
internal fun ToolPane(p: Pane) = when (p.ui.route.item) {
    BuiltInItem -> BuiltInTools(p)
    RulesItem -> AgentRules(p)
    SharedRulesItem -> SharedRules(p)
    else -> McpPane(p)
}

@Composable
private fun BuiltInTools(p: Pane) {
    val ui = p.ui
    val details = ui.config.details ?: return PaneLoading()
    val blocked = details.rules.deny.tools + ui.state.serverRules?.deny?.tools.orEmpty()
    DetailPane(p.back, "Built-in tools", error = ui.error, info = "Tools that come with Kinuvo. ${ui.agent?.name ?: "The agent"} only sees the ones turned on.") {
        RowGroup {
            TOOLS.forEach { (tool, label) ->
                val offered = ui.capabilities.offers(Integration.Tools, tool)
                GroupRow(label, subtitle = if (!offered) "Not available on this Server" else "Blocked by a rule".takeIf { tool in blocked }, enabled = offered, chevron = false) {
                    if (offered) Switch(tool in details.settings.tools, { p.actions.onToolAccess(ui.route.agentId, tool, it) })
                }
            }
        }
    }
}

/** How a tool runs for an Agent: [Ask] follows its Risk tolerance. */
internal enum class ToolRule { Allow, Ask, Block }

/** The stable key the Server gives [tool] of this MCP server when [agentId] uses it, as trust rules name it. */
internal fun McpServerInfo.toolKey(agentId: String, tool: String): String {
    val instance = if (url != null) "${if (scope == ConfigScope.Shared) "shared" else "agents/$agentId"}/$name" else name
    return ToolIdentity(PluginInstanceId("kinuvo.mcp", instance), tool).key
}

internal fun TrustRules.rule(key: String, tool: String) = when {
    deny.tools.any { it == key || it == tool } -> ToolRule.Block
    trusted.any { it == key || it.startsWith("$key ") } -> ToolRule.Allow
    else -> ToolRule.Ask
}

internal fun effectiveToolRule(server: ToolRule, agent: ToolRule) = when {
    server == ToolRule.Block || agent == ToolRule.Block -> ToolRule.Block
    server == ToolRule.Allow || agent == ToolRule.Allow -> ToolRule.Allow
    else -> ToolRule.Ask
}

/** These rules with [rule] for the tool [key], dropping any older rule for it. */
internal fun TrustRules.with(key: String, tool: String, rule: ToolRule) = copy(
    trusted = trusted.filterNot { it == key || it.startsWith("$key ") } + listOfNotNull(key.takeIf { rule == ToolRule.Allow }),
    deny = deny.copy(tools = deny.tools.filterNot { it == key || it == tool } + listOfNotNull(key.takeIf { rule == ToolRule.Block })),
)

/** Each tool of an MCP server with whether it runs, asks or is blocked for the open Agent; tests the server to list them. */
@Composable
internal fun McpToolRules(s: McpServerInfo, p: Pane) {
    val ui = p.ui
    val details = ui.config.details
    val shared = ui.state.serverRules ?: TrustRules()
    val test = ui.state.mcpTest
    LaunchedEffect(s.scope, s.name) { p.actions.onTestMcp(s.scope, s.name) }
    val ask = if (s.approval == "auto") "Auto" else "Ask"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Tools" + (test?.tools?.size?.let { " ($it)" } ?: ""), Modifier.padding(start = 6.dp))
        when {
            test == null -> Box(Modifier.fillMaxWidth().padding(16.dp)) { Spinner() }
            test.needsLogin -> Pill("Sign in to list its tools", tone = Tone.Warning, icon = Lucide.LogIn)
            test.error != null -> Pill("Couldn't connect: ${test.error}", tone = Tone.Danger, icon = Lucide.CircleX)
            test.tools.isEmpty() -> Text("It offers no tools.", color = color(mutedColor))
            else -> RowGroup {
                test.tools.forEach { tool ->
                    val key = s.toolKey(ui.route.agentId, tool)
                    val everyAgent = shared.rule(key, tool)
                    val hidden = !(s.includeTools?.contains(tool) ?: true) || tool in s.excludeTools
                    val agentRule = details?.rules?.rule(key, tool) ?: ToolRule.Ask
                    val rule = effectiveToolRule(everyAgent, agentRule)
                    GroupRow(tool, chevron = false, enabled = !hidden && details != null, subtitle = when {
                        hidden -> "Hidden by the server's settings"
                        everyAgent == ToolRule.Block -> "Blocked for every Agent"
                        everyAgent == ToolRule.Allow && agentRule != ToolRule.Block -> "Allowed by the Server"
                        else -> null
                    }) {
                        Segmented(listOf("Allow", ask, "Block"), rule.ordinal, Modifier, enabled = !hidden && details != null && everyAgent != ToolRule.Block, small = true) { i ->
                            details?.let { d -> p.actions.onSaveAgent(UpdateAgentParams(d.info.id, rules = d.rules.with(key, tool, ToolRule.entries[i]))) }
                        }
                    }
                }
            }
        }
        Text(if (s.approval == "auto") "Auto runs without asking, as the server is set to." else "Ask follows ${ui.agent?.name ?: "the agent"}'s Risk tolerance.",
            Modifier.padding(horizontal = 6.dp), fontSize = 12.sp, color = color(mutedColor))
    }
}

/** The open Agent's Risk tolerance and its own allowed and blocked tools and commands. */
@Composable
private fun AgentRules(p: Pane) {
    val d = p.ui.config.details ?: return PaneLoading()
    key(d) {
        var approvals by remember { mutableStateOf(d.settings.approvals) }
        val trusted = rememberTextFieldState(d.rules.trusted.joinToString("\n"))
        val shell = rememberTextFieldState(d.rules.shell.joinToString("\n"))
        val denyTools = rememberTextFieldState(d.rules.deny.tools.joinToString("\n"))
        val denyShell = rememberTextFieldState(d.rules.deny.shell.joinToString("\n"))
        val rules = TrustRules(trusted.lines(), shell.lines(), DenyRules(denyTools.lines(), denyShell.lines()))
        val changes = UpdateAgentParams(d.info.id, approvals = approvals.takeIf { it != d.settings.approvals }, rules = rules.takeIf { it != d.rules })
        val dirty = changes != UpdateAgentParams(d.info.id)
        DetailPane(p.back, "Risk tolerance and rules", error = p.ui.error,
            trailing = { FormActions("Save", dirty, p.ui.state.saved && !dirty, onSave = { p.actions.onSaveAgent(changes) }) }) {
            DetailGroup("Risk tolerance") {
                DetailRow("Ask before") { Picker(APPROVALS.keys.toList(), approvals, { APPROVALS[it] ?: it }, { approvals = it }) }
            }
            DetailGroup("${d.info.name}'s rules") {
                Text("The rules for every Agent apply too; their blocks win over these.", color = color(mutedColor), fontSize = 13.sp)
                FormField("Always allowed tools", "Full tool keys, one per line; use Always allow on an Approval to save the correct key and scope.") { TextArea(trusted, "tool:kinuvo.core/default/memory", lines = 2, capitalize = false) }
                FormField("Always allowed commands", "Commands that run with any further arguments, one per line. High-risk ones must match exactly.") { TextArea(shell, "git push\n./kotlin test", lines = 3, capitalize = false) }
                FormField("Blocked tools", "Never run: tool names (all instances) or full tool keys, one per line.") { TextArea(denyTools, "skill_manage", lines = 2, capitalize = false) }
                FormField("Blocked commands", "Patterns with * and ?, matched against each command and the whole line.") { TextArea(denyShell, "git push --force*", lines = 3, capitalize = false) }
            }
        }
    }
}

/** The rules every Agent follows, on top of its own. */
@Composable
private fun SharedRules(p: Pane) {
    val rules = p.ui.state.serverRules ?: return ServerLoading(p, "Rules for every Agent")
    key(rules) {
        val trusted = rememberTextFieldState(rules.trusted.joinToString("\n"))
        val shell = rememberTextFieldState(rules.shell.joinToString("\n"))
        val denyTools = rememberTextFieldState(rules.deny.tools.joinToString("\n"))
        val denyShell = rememberTextFieldState(rules.deny.shell.joinToString("\n"))
        val edited = TrustRules(trusted.lines(), shell.lines(), DenyRules(denyTools.lines(), denyShell.lines()))
        DetailPane(p.back, "Rules for every Agent", error = p.ui.error, info = "Each Agent adds its own on top. A block here wins over any Agent's Always allow.",
            trailing = { FormActions("Save", edited != rules, p.ui.state.saved && edited == rules, onSave = { p.actions.onSaveServerRules(edited) }) }) {
            DetailGroup("Blocked") {
                FormField("Tools", "Tool names (all instances) or full tool keys, one per line.") { TextArea(denyTools, "skill_manage", lines = 2, capitalize = false) }
                FormField("Commands", "Patterns with * and ?, matched against each command and the whole line.") { TextArea(denyShell, "git push --force*", lines = 2, capitalize = false) }
            }
            DetailGroup("Always allowed") {
                FormField("Tools", "Full tool keys, one per line.") { TextArea(trusted, "tool:kinuvo.core/default/memory", lines = 2, capitalize = false) }
                FormField("Commands", "Commands that run with any further arguments. High-risk ones must match exactly.") { TextArea(shell, "git status", lines = 2, capitalize = false) }
            }
        }
    }
}
