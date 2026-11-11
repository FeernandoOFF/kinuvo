package kinuvo.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.app.session.ChatItem
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.approval.ChangePreview
import kinuvo.protocol.approval.FieldChange
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SubAgentState
import kinuvo.protocol.session.SubAgentStatus
import kinuvo.protocol.tool.ToolNames
import kinuvo.ui.agent.AgentAvatar
import kinuvo.ui.design.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The open Session's Sub-agents by child Session id, and the children waiting on the user. */
internal class SubAgents(val children: Map<SessionId, SubAgentState> = emptyMap(), val waiting: Set<SessionId> = emptySet())

internal val LocalSubAgents = staticCompositionLocalOf { SubAgents() }

/** One row of a Sub-agent call: the child's latest state, or what was asked until the Server answers with it. */
private class ChildRow(val id: SessionId?, val agentId: AgentId?, val goal: String, val state: SubAgentState?)

private fun ChatItem.ToolCall.rows(children: Map<SessionId, SubAgentState>): List<ChildRow> {
    val args = runCatching { ProtocolJson.parseToJsonElement(args).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    fun text(key: String) = (args[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    fun row(id: SessionId, fallback: String = "") = children[id].let { ChildRow(id, it?.agentId, it?.goal ?: fallback, it) }
    return when (name) {
        ToolNames.AGENTS_SPAWN -> {
            val started = result?.takeIf { !isError }?.let { runCatching { ProtocolJson.decodeFromString(SubAgentState.serializer(), it) }.getOrNull() }
            listOf(started?.let { children[it.sessionId]?.let { live -> ChildRow(it.sessionId, live.agentId, live.goal, live) } ?: ChildRow(it.sessionId, it.agentId, it.goal, it) }
                ?: ChildRow(null, text("agent_id")?.let(::AgentId), text("goal").orEmpty(), null))
        }
        ToolNames.AGENTS_WAIT -> (args["session_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let { id -> row(SessionId(id)) } }
        else -> listOfNotNull(text("session_id")?.let { row(SessionId(it)) })
    }
}

/** An `agents_spawn`, `agents_wait` or `agents_send` call as the children it touches, each with its Agent, goal and live status, opening it on tap. */
@Composable
internal fun SubAgentCard(item: ChatItem.ToolCall, actions: ChatActions) {
    val subAgents = LocalSubAgents.current
    val rows = item.rows(subAgents.children)
    val needs = rows.count { it.id in subAgents.waiting }
    val running = rows.count { it.state?.status == SubAgentStatus.Running || (it.state == null && item.result == null) }
    val shape = Theme[shapes][mediumShape]
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, if (needs > 0) color(warningColor).copy(alpha = 0.6f) else color(borderColor), shape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = Spacing.space10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Icon(if (item.name == ToolNames.AGENTS_SEND) Lucide.MessageSquareReply else Lucide.Split, Modifier.size(15.dp), tint = color(mutedColor))
            val one = rows.singleOrNull()
            val who = agentName(one?.agentId, actions.agents)
            Text(when (item.name) {
                ToolNames.AGENTS_SPAWN -> "Sub-agent: $who"
                ToolNames.AGENTS_SEND -> "Follow-up to $who"
                else -> if (one != null) "Waiting on $who" else "Waiting on ${rows.size} Sub-agents"
            }, Modifier.weight(1f), style = TextStyles.callout, singleLine = true, overflow = TextOverflow.Ellipsis)
            when {
                item.waiting || needs > 0 -> Pill(if (rows.size > 1 && needs > 1) "$needs need you" else "Needs you", tone = Tone.Warning)
                item.isError && !item.denied -> Pill("Failed", tone = Tone.Danger)
                item.denied -> Pill("Denied")
                running > 0 -> Pill(if (rows.size > 1) "$running working" else "Working", tone = Tone.Accent, loading = true)
                else -> Pill("Done", tone = Tone.Success)
            }
        }
        rows.forEach { row -> key(row.id?.value) { ChildRowView(row, row.id in subAgents.waiting, showResult = item.name == ToolNames.AGENTS_WAIT, actions) } }
        item.result?.takeIf { item.isError }?.let { error ->
            Box(Modifier.padding(start = Spacing.space10, end = Spacing.space10, bottom = Spacing.space10)) {
                Expandable(Lucide.CircleX, "Error", tint = color(destructiveColor)) { CodeBlock(error, language = "Error", error = true) }
            }
        }
    }
}

@Composable
private fun ChildRowView(row: ChildRow, needsYou: Boolean, showResult: Boolean, actions: ChatActions) {
    val agent = actions.agents.firstOrNull { it.id == row.agentId }
    val id = row.id
    val open = if (id != null && row.agentId != null) ({ actions.onOpenSession(row.agentId, id) }) else null
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the Sub-agent's Session", onClick = open) else Modifier)
                .heightIn(min = 48.dp).padding(horizontal = Spacing.space10, vertical = Spacing.space6),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space10),
        ) {
            AgentAvatar(agent, 24.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                    Text(agent?.name ?: row.agentId?.value ?: "Agent", Modifier.weight(1f, fill = false), style = TextStyles.secondary, fontWeight = FontWeight.Medium, singleLine = true, overflow = TextOverflow.Ellipsis)
                    Badge("Hidden", Lucide.LockKeyhole)
                }
                Text(row.goal.lineSequence().firstOrNull().orEmpty(), style = TextStyles.caption, color = color(mutedColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when {
                needsYou -> Pill("Needs you", tone = Tone.Warning)
                else -> when (row.state?.status) {
                    null -> Text("Starting…", style = TextStyles.caption, color = color(mutedColor), singleLine = true)
                    SubAgentStatus.Running -> Spinner(size = 13.dp, tint = color(accentColor))
                    SubAgentStatus.Completed -> Icon(Lucide.CircleCheck, Modifier.size(15.dp), tint = color(successColor), contentDescription = "Completed")
                    SubAgentStatus.Failed -> Icon(Lucide.CircleX, Modifier.size(15.dp), tint = color(destructiveColor), contentDescription = "Failed")
                    SubAgentStatus.Cancelled -> Icon(Lucide.CircleSlash, Modifier.size(15.dp), tint = color(mutedColor), contentDescription = "Cancelled")
                }
            }
            if (open != null) Icon(Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
        }
        row.state?.result?.takeIf { showResult && row.state.status != SubAgentStatus.Running }?.let { result ->
            Box(Modifier.padding(start = Spacing.space10, end = Spacing.space10, bottom = Spacing.space10)) {
                Expandable(Lucide.CornerDownLeft, "Result") { Markdown(result) }
            }
        }
    }
}

private fun agentName(id: AgentId?, agents: List<AgentInfo>) = agents.firstOrNull { it.id == id }?.name ?: id?.value ?: "an Agent"

/** Above the composer while this Session's Sub-agents run: how many, and a way to the first that waits on the user. */
@Composable
internal fun SubAgentFooter(subAgents: SubAgents, agents: List<AgentInfo>, onOpen: (AgentId, SessionId) -> Unit, modifier: Modifier = Modifier) {
    val active = subAgents.children.values.filter { it.status == SubAgentStatus.Running }
    if (active.isEmpty()) return
    val needs = active.firstOrNull { it.sessionId in subAgents.waiting }
    val target = needs ?: active.first()
    Row(
        modifier.clip(PillShape).background((if (needs != null) color(warningColor) else color(accentColor)).copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClickLabel = "Open the Sub-agent's Session") { onOpen(target.agentId, target.sessionId) }
            .heightIn(min = 36.dp).padding(horizontal = Spacing.space12, vertical = Spacing.space6),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
    ) {
        if (needs != null) Icon(Lucide.Hand, Modifier.size(14.dp), tint = color(warningColor)) else Spinner(size = 12.dp, tint = color(accentColor))
        val who = agentName(target.agentId, agents)
        Text(
            when {
                needs != null -> "$who needs you" + if (active.size > 1) " · ${active.size} Sub-agents" else ""
                active.size == 1 -> "Waiting on $who"
                else -> "Waiting on ${active.size} Sub-agents"
            },
            style = TextStyles.caption, singleLine = true, overflow = TextOverflow.Ellipsis,
        )
        Icon(Lucide.ArrowRight, Modifier.size(12.dp), tint = color(mutedColor))
    }
}

/** Atop a Sub-agent's Session: which Agent started it, a way back, and what Hidden means for it. */
@Composable
internal fun ParentBanner(info: SessionInfo, agents: List<AgentInfo>, onOpen: (AgentId, SessionId) -> Unit) {
    val parent = info.parent ?: return
    val agent = agents.firstOrNull { it.id == parent.agentId }
    Panel(Modifier.fillMaxWidth(), color = color(subtleColor), borderColor = Color.Transparent, contentPadding = PaddingValues(Spacing.space10)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            AgentAvatar(agent, 20.dp)
            Text("Sub-agent of ${agent?.name ?: parent.agentId.value}", Modifier.weight(1f), style = TextStyles.secondary, singleLine = true, overflow = TextOverflow.Ellipsis)
            Button(onClick = { onOpen(parent.agentId, parent.sessionId) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(accentColor)) {
                Icon(Lucide.ArrowLeft, Modifier.size(14.dp))
                Text("Open parent", style = TextStyles.caption, singleLine = true)
            }
        }
        if (info.hidden) Row(Modifier.padding(top = Spacing.space6), horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Icon(Lucide.LockKeyhole, Modifier.size(14.dp).padding(top = Spacing.space2), tint = color(mutedColor))
            Text("Hidden: no Agent can read this conversation. It runs on this device's model, and its result goes back only if you allow it.", style = TextStyles.caption, color = color(mutedColor))
        }
    }
}

/** An Approval's changes, field by field: the current value struck through, the new one beside it, then any content to read whole. */
@Composable
internal fun ChangePreviewView(preview: ChangePreview) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Text(preview.title, style = TextStyles.caption, color = color(mutedColor))
        if (preview.changes.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.menu)).border(1.dp, color(borderColor), RoundedCornerShape(Radius.menu))) {
            preview.changes.forEachIndexed { i, change ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(color(borderColor)))
                FieldChangeRow(change)
            }
        }
        preview.body?.let { body ->
            Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(Radius.menu)).background(color(subtleColor)).padding(Spacing.space12)) { Markdown(body) }
        }
        preview.note?.let { Text(it, style = TextStyles.caption, color = color(mutedColor)) }
    }
}

@Composable
private fun FieldChangeRow(change: FieldChange) {
    var expanded by remember(change) { mutableStateOf(false) }
    val long = listOfNotNull(change.before, change.after).any { '\n' in it || it.length > SHORT_VALUE }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.space10, vertical = Spacing.space8), verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(change.field, Modifier.weight(1f), style = TextStyles.caption, fontWeight = FontWeight.Medium, color = color(mutedColor), singleLine = true)
            if (long) Button(onClick = { expanded = !expanded }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(accentColor)) {
                Text(if (expanded) "Show less" else "Show all", style = TextStyles.caption, singleLine = true)
            }
        }
        val lines = if (expanded) Int.MAX_VALUE else 3
        change.before?.let { Value(it, removed = true, lines) }
        val after = change.after
        if (after != null) Value(after, removed = false, lines)
        else Text("Removed", style = TextStyles.caption, color = color(destructiveColor))
    }
}

@Composable
private fun Value(text: String, removed: Boolean, lines: Int) {
    val tone = if (removed) color(destructiveColor) else color(successColor)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.menu)).background(tone.copy(alpha = 0.08f)).padding(horizontal = Spacing.space8, vertical = Spacing.space4),
        horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        Text(if (removed) "−" else "+", style = MonoStyle, color = tone)
        val strike = removed && '\n' !in text && text.length <= SHORT_VALUE
        Text(text, Modifier.weight(1f), style = TextStyles.secondary.copy(textDecoration = if (strike) TextDecoration.LineThrough else null),
            color = if (removed) color(mutedColor) else color(onBackgroundColor), maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

private const val SHORT_VALUE = 120
