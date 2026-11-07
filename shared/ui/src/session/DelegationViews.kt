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
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.ChangePreview
import kinuvo.protocol.FieldChange
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionStatus
import kinuvo.ui.agent.AgentAvatar
import kinuvo.ui.design.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** One task of a `delegate` call: its Session once started, or what was asked until then. */
private class Delegated(val agentId: String?, val goal: String, val hidden: Boolean, val session: SessionInfo?)

private fun ChatItem.ToolCall.delegated(children: List<SessionInfo>, self: String?): List<Delegated> {
    val started = children.filter { it.parent?.toolCallId == callId }
    val args = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    val asked = (args["tasks"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(args)
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    // Tasks start in order, so the first ones asked are the ones already running.
    return asked.mapIndexed { i, task ->
        val session = started.getOrNull(i)
        Delegated(session?.agentId ?: task.text("agent") ?: self, session?.title ?: task.text("goal").orEmpty(), session?.hidden ?: ((task["hidden"] as? JsonPrimitive)?.booleanOrNull == true), session)
    } + started.drop(asked.size).map { Delegated(it.agentId, it.title, it.hidden, it) }
}

/** A `delegate` call as the Sessions it started: each with its Agent, goal and live state, opening it on tap. */
@Composable
internal fun DelegationCard(item: ChatItem.ToolCall, actions: ChatActions) {
    val tasks = item.delegated(actions.children, actions.agentId)
    val agents = actions.agents
    val waiting = tasks.count { it.session?.status == SessionStatus.Waiting }
    val running = tasks.count { it.session?.status == SessionStatus.Running || (it.session == null && item.result == null) }
    val background = "\"background\"\\s*:\\s*true".toRegex().containsMatchIn(item.args)
    val shape = Theme[shapes][mediumShape]
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, color(if (waiting > 0) warningColor else borderColor).copy(alpha = if (waiting > 0) 0.6f else 1f), shape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = Spacing.space10), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Icon(Lucide.Split, Modifier.size(15.dp), tint = color(mutedColor))
            val one = tasks.singleOrNull()
            Text(if (one != null) "Delegated to ${agentName(one.agentId, agents)}" else "Delegated ${tasks.size} tasks", Modifier.weight(1f), style = TextStyles.callout, singleLine = true, overflow = TextOverflow.Ellipsis)
            if (background) Text("background", style = TextStyles.caption, color = color(mutedColor), singleLine = true)
            when {
                item.waiting -> Pill("Needs you", tone = Tone.Warning)
                waiting > 0 -> Pill(if (tasks.size == 1) "Needs you" else if (waiting == 1) "1 needs you" else "$waiting need you", tone = Tone.Warning)
                running > 0 -> Pill(if (tasks.size > 1) "$running working" else "Working", tone = Tone.Accent, loading = true)
                item.isError && !item.denied -> Pill("Failed", tone = Tone.Danger)
                item.denied -> Pill("Denied")
                else -> Pill("Done", tone = Tone.Success)
            }
        }
        tasks.forEach { task -> DelegatedRow(task, agents, actions) }
        item.result?.takeIf { !background }?.let { result ->
            Box(Modifier.padding(start = Spacing.space10, end = Spacing.space10, bottom = Spacing.space10)) {
                Expandable(if (item.isError) Lucide.CircleX else Lucide.CornerDownLeft, if (item.isError) "Error" else "Result handed back", tint = if (item.isError) color(destructiveColor) else Color.Unspecified) {
                    if (item.isError) CodeBlock(result, language = "Error", error = true) else Markdown(result)
                }
            }
        }
    }
}

@Composable
private fun DelegatedRow(task: Delegated, agents: List<AgentInfo>, actions: ChatActions) {
    val session = task.session
    val agent = agents.firstOrNull { it.id == task.agentId }
    val open = session?.let { { actions.onOpenSession(it.agentId, it.id) } }
    Row(
        Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the Session", onClick = open) else Modifier)
            .heightIn(min = 48.dp).padding(horizontal = Spacing.space10, vertical = Spacing.space6),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space10),
    ) {
        AgentAvatar(agent, 24.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                Text(agent?.name ?: task.agentId ?: "Agent", Modifier.weight(1f, fill = false), style = TextStyles.secondary, fontWeight = FontWeight.Medium, singleLine = true, overflow = TextOverflow.Ellipsis)
                if (task.hidden) Badge("Hidden", Lucide.LockKeyhole)
            }
            Text(task.goal.lineSequence().firstOrNull().orEmpty(), style = TextStyles.caption, color = color(mutedColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when (session?.status) {
            null -> Text("Starting…", style = TextStyles.caption, color = color(mutedColor), singleLine = true)
            SessionStatus.Running -> Spinner(size = 13.dp, tint = color(accentColor))
            SessionStatus.Waiting -> Pill("Needs you", tone = Tone.Warning)
            SessionStatus.Idle -> Icon(Lucide.CircleCheck, Modifier.size(15.dp), tint = color(successColor))
        }
        if (open != null) Icon(Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
    }
}

private fun agentName(id: String?, agents: List<AgentInfo>) = agents.firstOrNull { it.id == id }?.name ?: id ?: "an Agent"

/** Above the composer while this Session's delegations run: how many, and a way to the first that waits on the user. */
@Composable
internal fun DelegationFooter(children: List<SessionInfo>, agents: List<AgentInfo>, onOpen: (agentId: String, sessionId: String) -> Unit, modifier: Modifier = Modifier) {
    val active = children.filter { it.status != SessionStatus.Idle }
    if (active.isEmpty()) return
    val needs = active.firstOrNull { it.status == SessionStatus.Waiting }
    val target = needs ?: active.first()
    val shape = PillShape
    Row(
        modifier.clip(shape).background(color(if (needs != null) warningColor else accentColor).copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClickLabel = "Open the Session") { onOpen(target.agentId, target.id) }
            .heightIn(min = 36.dp).padding(horizontal = Spacing.space12, vertical = Spacing.space6),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
    ) {
        if (needs != null) Icon(Lucide.Hand, Modifier.size(14.dp), tint = color(warningColor)) else Spinner(size = 12.dp, tint = color(accentColor))
        val who = agentName(target.agentId, agents)
        Text(
            when {
                needs != null -> "$who needs you" + if (active.size > 1) " · ${active.size} delegated Sessions" else ""
                active.size == 1 -> "Waiting on $who"
                else -> "Waiting on ${active.size} delegated Sessions"
            },
            style = TextStyles.caption, singleLine = true, overflow = TextOverflow.Ellipsis,
        )
        Icon(Lucide.ArrowRight, Modifier.size(12.dp), tint = color(mutedColor))
    }
}

/** Atop a delegated Session: who handed it over, a way back, and what Hidden means for it. */
@Composable
internal fun DelegatedBanner(info: SessionInfo, agents: List<AgentInfo>, onOpen: (agentId: String, sessionId: String) -> Unit) {
    val parent = info.parent ?: return
    val agent = agents.firstOrNull { it.id == parent.agentId }
    Panel(Modifier.fillMaxWidth(), color = color(subtleColor), borderColor = Color.Transparent, contentPadding = PaddingValues(Spacing.space10)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            AgentAvatar(agent, 20.dp)
            Text("Delegated by ${agent?.name ?: parent.agentId}", Modifier.weight(1f), style = TextStyles.secondary, singleLine = true, overflow = TextOverflow.Ellipsis)
            Button(onClick = { onOpen(parent.agentId, parent.sessionId) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(accentColor)) {
                Icon(Lucide.ArrowLeft, Modifier.size(14.dp))
                Text("Open parent", style = TextStyles.caption, singleLine = true)
            }
        }
        if (info.hidden) Row(Modifier.padding(top = Spacing.space6), horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Icon(Lucide.LockKeyhole, Modifier.size(14.dp).padding(top = Spacing.space2), tint = color(mutedColor))
            Text("Hidden: no Agent can read this conversation. It stays on this device's model, and only its final answer goes back.", style = TextStyles.caption, color = color(mutedColor))
        }
    }
}

/** An Approval's changes, field by field: what's there now struck through, what it becomes beside it. */
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
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.menu)).background(color(subtleColor)).padding(Spacing.space12)) { Markdown(body) }
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
