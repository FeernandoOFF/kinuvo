package kinuvo.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.AgentInfo
import kinuvo.ui.agent.AgentAvatar
import kinuvo.ui.design.*
import kinuvo.ui.session.HeaderButton
import kinuvo.ui.session.TitleBarRow

/** How Home's pages reach the shell: [onToggleShell] shows it, beside the screens when [wide], from under them otherwise. */
class HomeChrome(val onToggleShell: () -> Unit, val wide: Boolean)

val LocalHomeChrome = staticCompositionLocalOf<HomeChrome?> { null }

/** A page's button: a bubble on phones, an icon in the title bar otherwise. */
class PageAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The Agent a page shows, picked in its header; with [everyone], also none, e.g. "All agents". */
class AgentScope(val agents: List<AgentInfo>, val agentId: String?, val everyone: String? = null, val onSelect: (String?) -> Unit) {
    val agent get() = agents.firstOrNull { it.id == agentId }
    val label get() = agent?.name ?: everyone.orEmpty()
}

/**
 * The header of Home's pages, e.g. Today, Routines or Tools: the shell's toggle, never Back, the page's name and any
 * [scope]. Phones get the chat's bubbles and pill, the pill picking the Agent; pointers a title bar.
 */
@Composable
fun PageHeader(icon: ImageVector, title: String, subtitle: String? = null, scope: AgentScope? = null, actions: List<PageAction> = emptyList()) {
    val chrome = LocalHomeChrome.current
    val phone = chrome?.wide != true && LocalInteractionMode.current == InteractionMode.Touch
    if (phone) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chrome?.let { FloatingBubble(Lucide.Menu, "Home and agents", it.onToggleShell) }
        if (scope != null) AgentMenu(scope, Modifier.weight(1f)) { open ->
            Row { FloatingPill(open, "$title, ${scope.label}, change Agent", fill = true, leading = { ScopeAvatar(scope, 26.dp) }) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(scope.label, fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
                    Icon(Lucide.ChevronDown, Modifier.size(12.dp), tint = color(mutedColor))
                }
            } }
        } else FloatingPill(chrome?.onToggleShell ?: {}, listOfNotNull(title, subtitle).joinToString(", "), fill = true,
            leading = { Icon(icon, Modifier.size(18.dp), tint = color(mutedColor)) }) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, singleLine = true)
            subtitle?.let { Text(it, fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis) }
        }
        if (actions.isNotEmpty()) Bubble { actions.forEach { BubbleButton(it.icon, it.label, it.onClick) } }
    } else {
        TitleBarRow {
            chrome?.let { if (it.wide) HeaderButton(Lucide.PanelLeft, "Toggle sidebar ${shortcut(".")}", it.onToggleShell) else HeaderButton(Lucide.Menu, "Home and agents", it.onToggleShell) }
            Text(title, Modifier.padding(start = 6.dp), fontWeight = FontWeight.Normal, singleLine = true)
            subtitle?.let { Text(it, Modifier.weight(1f, fill = false).padding(start = 8.dp), color = color(mutedColor), fontSize = 13.sp, singleLine = true, overflow = TextOverflow.Ellipsis) }
            scope?.let { s ->
                AgentMenu(s, Modifier.padding(start = 6.dp)) { open ->
                    Button(onClick = open, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.titleBarControl()) {
                        ScopeAvatar(s, 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(s.label, singleLine = true)
                        Spacer(Modifier.width(4.dp))
                        Icon(Lucide.ChevronDown, Modifier.size(14.dp), tint = color(mutedColor))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            actions.forEach { HeaderButton(it.icon, it.label, it.onClick) }
        }
        HorizontalSeparator()
    }
}

/** A menu of [scope]'s Agents, opened by [anchor]. */
@Composable
private fun AgentMenu(scope: AgentScope, modifier: Modifier, anchor: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        DropdownMenu(
            expanded = open,
            onExpandedChange = { open = it },
            panel = {
                DropdownMenuPanel {
                    scope.everyone?.let { all ->
                        DropdownMenuItem(onClick = { open = false; scope.onSelect(null) }, trailing = if (scope.agentId == null) ({ Icon(Lucide.Check, Modifier.size(14.dp)) }) else null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Lucide.Users, Modifier.size(20.dp), tint = color(mutedColor))
                                Text(all, singleLine = true)
                            }
                        }
                    }
                    scope.agents.forEach { agent ->
                        DropdownMenuItem(
                            onClick = { open = false; scope.onSelect(agent.id) },
                            trailing = if (agent.id == scope.agentId) ({ Icon(Lucide.Check, Modifier.size(14.dp)) }) else null,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AgentAvatar(agent, 20.dp)
                                Text(agent.name, singleLine = true)
                            }
                        }
                    }
                }
            },
        ) { anchor { open = true } }
    }
}

@Composable
private fun ScopeAvatar(scope: AgentScope, size: androidx.compose.ui.unit.Dp) {
    val agent = scope.agent
    if (agent != null) AgentAvatar(agent, size) else Icon(Lucide.Users, Modifier.size(size * 0.7f), tint = color(mutedColor))
}
