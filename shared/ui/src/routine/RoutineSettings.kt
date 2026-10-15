package kinuvo.ui.routine

import kinuvo.app.connection.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
internal fun RoutineList(p: Pane) {
    val ui = p.ui
    val routines = ui.state.routines
    ItemList(
        p, "Routines",
        "Prompts ${ui.agent?.name ?: "the agent"} runs on a schedule, each in its own session. Add one here or ask in chat, " +
            "e.g. “every weekday at 9, summarize my inbox”.",
        "New routine", routines == null,
    ) {
        items(routines.orEmpty(), key = { it.id }) { r ->
            ItemRow(r.id == ui.route.item, r.icon(), r.name, r.summary(ui.now), { p.go(ui.route.copy(item = r.id, editing = false)) }) { r.statusPill() }
        }
    }
}

/** The open Routine, or its form when editing (a new one when no Routine is open). */
@Composable
internal fun RoutinePane(p: Pane) {
    val route = p.ui.route
    val routine = p.ui.state.routine
    when {
        route.editing && route.item == null -> RoutineForm(null, p)
        routine == null -> if (p.ui.state.routines == null) PaneLoading() else DetailPane(p.back, "Not found") {}
        route.editing -> RoutineForm(routine, p)
        else -> RoutineDetail(routine, p)
    }
}

@Composable
private fun RoutineDetail(r: Routine, p: Pane) {
    val (ui, actions) = p.ui to p.actions
    DetailPane(p.back, r.name, trailing = {
        Text(if (r.enabled) "On" else "Off", fontSize = 12.sp, color = color(mutedColor))
        Switch(r.enabled, { actions.onEnabled(r.id, it) }, Modifier.titleBarControl())
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Lucide.Play, "Run now", { actions.onRunNow(r.id) })
            ActionButton(Lucide.Pencil, "Edit", { p.go(ui.route.copy(editing = true)) })
            if (!r.heartbeat) DeleteButton("routine", r.id) { actions.onRemove(r.id) }
        }
        ui.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        DetailGroup("Schedule") {
            DetailRow("When") { Text(r.trigger.describe()) }
            DetailRow("Time zone") { Text(r.timeZone) }
            DetailRow("Active hours") { Text(r.activeHours?.let { "${it.start}–${it.end}" } ?: "Any time") }
            DetailRow("Next run") { Text(r.nextRun(ui.now)) }
        }
        DetailGroup("Runs") {
            DetailRow("Session") {
                val attached = r.sessionId
                if (attached == null) Text("A new session each run")
                else Button(onClick = { actions.onOpenSession(attached) }, style = ButtonStyle.Link) { Text("Continues one session") }
            }
            DetailRow("Approvals") { Picker(RoutineApprovals.entries, r.approvals, { it.label() }, { actions.onApprovals(r.id, it) }, hint = { it.hint() }) }
            DetailRow("Last run") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.lastRunAt?.let { relative(it, ui.now) } ?: "Never")
                    when {
                        r.lastStatus == RunStatus.Silent && r.heartbeat -> Pill("Skipped")
                        r.lastStatus == RunStatus.Silent -> Unit
                        else -> r.lastStatus?.let { Pill(it.label(), tone = it.tone()) }
                    }
                }
            }
            r.lastError?.let { DetailRow("") { Text(it, color = color(if (r.lastStatus == RunStatus.Error) destructiveColor else mutedColor), fontSize = 13.sp) } }
        }
        DetailGroup(if (r.heartbeat) "Checklist (HEARTBEAT.md)" else "Prompt") {
            if (r.prompt.isBlank()) Text("Empty. Edit it here, or ask the agent to add items to its Heartbeat checklist.", color = color(mutedColor), fontSize = 13.sp)
            else Markdown(r.prompt, Modifier.fillMaxWidth())
        }
        DetailGroup("Recent runs") {
            if (ui.state.runs.isEmpty()) Text(if (r.sessionId != null) "Runs continue the attached session." else "No runs yet.", color = color(mutedColor), fontSize = 13.sp)
            ui.state.runs.forEach { session ->
                ListRow(selected = false, onClick = { actions.onOpenSession(session.id) }, leading = { Icon(Lucide.MessageSquare, Modifier.size(15.dp)) }) {
                    Text(session.id.toRunLabel(), Modifier.weight(1f), singleLine = true)
                    if (session.status != SessionStatus.Idle) Pill(if (session.status == SessionStatus.Running) "Running" else "Waiting", tone = Tone.Accent)
                    Icon(Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
                }
            }
        }
    }
}

private enum class ScheduleKind(val label: String, val placeholder: String, val hint: String) {
    Every("Every", "30m", "An interval: 30m, 2h, 1d"),
    Cron("Cron", "0 9 * * MON-FRI", "minute hour day month weekday"),
    Once("Once", "2026-05-01T09:00", "A local date and time"),
}

/** Creates a Routine when [r] is null; otherwise edits it. */
@Composable
private fun RoutineForm(r: Routine?, p: Pane) {
    val name = rememberTextFieldState(r?.name.orEmpty())
    val prompt = rememberTextFieldState(r?.prompt.orEmpty())
    var kind by remember { mutableStateOf(r?.trigger?.kind() ?: ScheduleKind.Every) }
    val schedule = rememberTextFieldState(r?.let { it.trigger.text(it.timeZone) }.orEmpty())
    val zone = rememberTextFieldState(r?.timeZone.orEmpty())
    val hours = rememberTextFieldState(r?.activeHours?.let { "${it.start}-${it.end}" }.orEmpty())
    var approvals by remember { mutableStateOf(r?.approvals ?: RoutineApprovals.Inherit) }
    val ready = name.value().isNotEmpty() && (r?.heartbeat == true || prompt.value().isNotEmpty()) && schedule.value().isNotEmpty()
    val save = {
        val value = schedule.value()
        p.actions.onSaveRoutine(r?.id, RoutineSpec(
            name = name.value(),
            prompt = prompt.text.toString().trim(),
            at = value.takeIf { kind == ScheduleKind.Once },
            every = value.takeIf { kind == ScheduleKind.Every },
            cron = value.takeIf { kind == ScheduleKind.Cron },
            timeZone = zone.value().ifEmpty { null },
            activeHours = hours.value().ifEmpty { "none" },
            approvals = approvals,
        ))
    }
    DetailPane(p.back, r?.let { "Edit ${it.name}" } ?: "New routine", error = p.ui.error, trailing = { FormActions(if (r == null) "Create routine" else "Save", ready, p.ui.state.saved, onSave = save, onCancel = p.cancel) }) {
        FormField("Name") { Input(name, "Morning brief", capitalize = true) }
        FormField(if (r?.heartbeat == true) "Checklist (HEARTBEAT.md)" else "Prompt", "Runs with only the agent's instructions and Memory, so make it self-contained. If it needs you, it notifies you and waits.") {
            TextArea(prompt, "Summarize my unread email and list anything urgent.")
        }
        FormField("Schedule", kind.hint) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(ScheduleKind.entries, kind, { it.label }, { kind = it })
                Box(Modifier.weight(1f)) { Input(schedule, kind.placeholder) }
            }
        }
        FormField("Time zone", "IANA name, e.g. Europe/Dublin. Blank for yours.") { Input(zone, "Europe/Dublin") }
        FormField("Active hours", "Only fire between these local times. Blank for any time.") { Input(hours, "08:00-22:00") }
        FormField("Approvals") { Picker(RoutineApprovals.entries, approvals, { it.label() }, { approvals = it }, hint = { it.hint() }) }
    }
}

private fun Trigger.kind() = when (this) {
    is Trigger.Once -> ScheduleKind.Once
    is Trigger.Every -> ScheduleKind.Every
    is Trigger.Cron -> ScheduleKind.Cron
}

/** The schedule as typed in the form. */
private fun Trigger.text(zone: String): String = when (this) {
    is Trigger.Once -> Instant.fromEpochMilliseconds(at).toLocalDateTime(runCatching { TimeZone.of(zone) }.getOrDefault(TimeZone.UTC))
        .let { LocalDateTime(it.year, it.month, it.day, it.hour, it.minute).toString() }
    is Trigger.Every -> when {
        minutes % 1440 == 0L -> "${minutes / 1440}d"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
    is Trigger.Cron -> expr
}

@Composable
private fun Routine.statusPill() = when {
    !enabled && trigger is Trigger.Once && lastRunAt != null -> Pill("Done")
    !enabled -> Pill("Off")
    lastStatus == RunStatus.Running -> Pill("Running", tone = Tone.Accent)
    lastStatus == RunStatus.Error -> Pill("Failed", tone = Tone.Danger)
    else -> Unit
}

private fun Routine.icon(): ImageVector = if (heartbeat) Lucide.HeartPulse else Lucide.AlarmClock

internal fun Routine.summary(now: Long) = "${trigger.describe()} · " + when {
    !enabled && trigger is Trigger.Once && lastRunAt != null -> "done"
    !enabled -> "off"
    else -> nextRunAt?.let { "next ${relative(it, now)}" } ?: "not scheduled"
}

private fun Routine.nextRun(now: Long): String = when {
    !enabled && trigger is Trigger.Once && lastRunAt != null -> "Done"
    !enabled -> "Off"
    else -> nextRunAt?.let { relative(it, now).replaceFirstChar(Char::uppercase) } ?: "Not scheduled"
}

private fun RunStatus.label() = when (this) {
    RunStatus.Ok -> "OK"
    RunStatus.Error -> "Failed"
    RunStatus.Skipped -> "Skipped"
    RunStatus.Silent -> "Silent"
    RunStatus.Stopped -> "Stopped"
    RunStatus.Running -> "Running"
}

private fun RunStatus.tone() = when (this) {
    RunStatus.Ok -> Tone.Success
    RunStatus.Error -> Tone.Danger
    RunStatus.Skipped -> Tone.Warning
    RunStatus.Silent -> Tone.Neutral
    RunStatus.Stopped -> Tone.Neutral
    RunStatus.Running -> Tone.Accent
}

private fun RoutineApprovals.label() = when (this) {
    RoutineApprovals.Inherit -> "Ask me"
    RoutineApprovals.Auto -> "Run without asking"
    RoutineApprovals.Deny -> "Deny"
}

private fun RoutineApprovals.hint() = when (this) {
    RoutineApprovals.Inherit -> "Wait for my Approval, as in chat"
    RoutineApprovals.Auto -> "Run every tool call unattended"
    RoutineApprovals.Deny -> "Deny calls that need an Approval and never ask me"
}

/** `20261001-090000-abcd` → `2026-10-01 09:00`. */
private fun String.toRunLabel(): String =
    if (length >= 15 && this[8] == '-') "${substring(0, 4)}-${substring(4, 6)}-${substring(6, 8)} ${substring(9, 11)}:${substring(11, 13)}" else this

/** E.g. "every 30 min", "every day at 09:00", "09:30 on MON-FRI". */
fun Trigger.describe(): String = when (this) {
    is Trigger.Once -> "Once"
    is Trigger.Every -> when {
        minutes % 1440 == 0L -> if (minutes == 1440L) "Every day" else "Every ${minutes / 1440} days"
        minutes % 60 == 0L -> if (minutes == 60L) "Every hour" else "Every ${minutes / 60} hours"
        else -> "Every $minutes min"
    }
    is Trigger.Cron -> {
        val f = expr.trim().split(Regex("\\s+"))
        val time = f.takeIf { it.size == 5 }?.let { (m, h) -> m.toIntOrNull()?.let { mm -> h.toIntOrNull()?.let { hh -> "${pad(hh)}:${pad(mm)}" } } }
        when {
            time == null -> "Cron $expr"
            f[2] == "*" && f[3] == "*" && f[4] == "*" -> "Every day at $time"
            f[2] == "*" && f[3] == "*" -> "$time on ${f[4]}"
            else -> "Cron $expr"
        }
    }
}

private fun pad(n: Int) = n.toString().padStart(2, '0')
