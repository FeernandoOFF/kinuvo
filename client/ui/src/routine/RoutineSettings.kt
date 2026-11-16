package kinuvo.ui.routine

import kinuvo.ui.design.HeaderButton
import kinuvo.app.connection.*
import kinuvo.ui.settings.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.session.*
import kinuvo.ui.design.*
import kinuvo.ui.workflow.LocalWorkflows
import kinuvo.ui.workflow.summary
import kinuvo.protocol.workflow.WorkflowId
import kinuvo.ui.workflow.workflowItem
import kinuvo.ui.workflow.workflowOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Routines and Workflows in one place, as two tables: the picked Agent's Routines, then the Server's Workflows. */
@Composable
internal fun RoutineList(p: Pane) {
    val ui = p.ui
    val routines = ui.state.routines
    val error = ui.state.routinesError
    val restricted = !ui.management
    val workflows = LocalWorkflows.current
    val agent = ui.agent?.name ?: "the Agent"
    val newRoutine = { p.go(ui.route.copy(item = null, editing = true)) }
    ItemList(
        p, if (workflows != null) "Routines & Workflows" else "Routines",
        if (workflows != null) "Routines run one prompt on a schedule. Workflows chain several steps, Agents and Approvals." else "Prompts $agent runs on a schedule, each in its own Session.",
        // Each table loads on its own, so Workflows show while Routines are still on their way.
        "New routine", workflows == null && routines == null && error == null && !restricted, canAdd = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            TableHeader(if (workflows != null) "Routines · $agent" else "Routines", "New routine", newRoutine.takeIf { !restricted && error == null })
            when {
                restricted -> ManagementAccessRequired()
                error != null -> LoadFailure(error, onAction = p.actions.onReloadRoutines)
                routines == null -> Box(Modifier.fillMaxWidth().padding(Spacing.space12), contentAlignment = Alignment.Center) { Spinner() }
                routines.isEmpty() -> RowGroup {
                    GroupRow("No routines yet", subtitle = "Prompts $agent runs on a schedule. Add one, or ask in chat, e.g. “every weekday at 9, summarize my inbox”.", icon = Lucide.AlarmClock, tint = color(mutedColor))
                }
                else -> RowGroup { routines.forEach { r ->
                    ItemRow(r.id == ui.route.item, r.icon(), r.name, r.summary(ui.now), { p.go(ui.route.copy(item = r.id, editing = false)) }, muted = !r.enabled) { r.statusPill() }
                } }
            }
        }
        if (workflows != null) Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            // Opens beside the list, like a Routine, where the page has room for a detail; otherwise on its own screen.
            val open = { id: WorkflowId -> if (workflows.detail != null) p.go(ui.route.copy(item = workflowItem(id), editing = false)) else workflows.onOpen(id) }
            TableHeader("All Workflows", "New workflow", workflows.onNew?.takeIf { !restricted }?.let { create -> { create(open) } })
            val list = workflows.workflows
            when {
                workflows.error != null -> LoadFailure(workflows.error, onAction = workflows.onReload)
                list == null -> Box(Modifier.fillMaxWidth().padding(Spacing.space12), contentAlignment = Alignment.Center) { Spinner() }
                list.isEmpty() -> RowGroup {
                    GroupRow("No workflows yet", subtitle = "Chain Agent turns and Approvals into steps the Server runs for you, on demand or on a schedule.", icon = Lucide.Workflow, tint = color(mutedColor))
                }
                else -> RowGroup { list.sortedByDescending { it.createdAt }.forEach { w ->
                    ItemRow(workflowOf(ui.route.item) == w.id, Lucide.Workflow, w.name, summary(w), { open(w.id) }, muted = !w.enabled) { if (!w.enabled) Pill("Off") }
                } }
            }
        }
    }
}

/** A table's caption with its Add button. */
@Composable
private fun TableHeader(title: String, addLabel: String, onAdd: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = Spacing.space6), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f))
        onAdd?.let {
            Button(onClick = it, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(accentColor)) {
                Icon(Lucide.Plus, Modifier.size(14.dp))
                Text(addLabel, style = TextStyles.label, singleLine = true)
            }
        }
    }
}

/** The open Routine, or its form when editing (a new one when no Routine is open). */
@Composable
internal fun RoutinePane(p: Pane) {
    val route = p.ui.route
    val workflow = workflowOf(route.item)
    val workflows = LocalWorkflows.current
    // Beside the list, the page header already owns the sidebar toggle.
    if (workflow != null && workflows?.detail != null) return CompositionLocalProvider(LocalHomeChrome provides null) {
        workflows.detail(workflow, p.back) { p.go(route.copy(item = null, editing = false)) }
    }
    val routine = p.ui.state.routine
    when {
        !p.ui.management -> DetailPane(p.back, "Routines") { ManagementAccessRequired() }
        p.ui.state.routines == null && p.ui.state.routinesError != null -> DetailPane(p.back, "Routines") { LoadFailure(p.ui.state.routinesError.orEmpty(), onAction = p.actions.onReloadRoutines) }
        route.editing && route.item == null -> RoutineForm(null, p)
        routine == null -> if (p.ui.state.routines == null) PaneLoading() else DetailPane(p.back, "Not found") {}
        route.editing -> RoutineForm(routine, p)
        else -> RoutineDetail(routine, p)
    }
}

@Composable
private fun RoutineDetail(r: Routine, p: Pane) {
    val (ui, actions) = p.ui to p.actions
    DetailPane(p.back, r.name, trailing = { HeaderButton(Lucide.Pencil, "Edit", { p.go(ui.route.copy(editing = true)) }) }) {
        ui.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        RowGroup {
            GroupRow("Runs on schedule", subtitle = if (r.enabled) r.nextRun(ui.now) else "Off", chevron = false, toggled = r.enabled, onToggle = { actions.onEnabled(r.id, it) })
            ActionRow("Run now", Lucide.Play, { actions.onRunNow(r.id) })
        }
        DetailGroup("Schedule") {
            DetailRow("When") { Text(r.trigger.describe()) }
            DetailRow("Time zone") { Text(r.timeZone) }
            DetailRow("Active hours") { Text(r.activeHours?.let { "${it.start}–${it.end}" } ?: "Any time") }
            DetailRow("Next run") { Text(r.nextRun(ui.now)) }
        }
        DetailGroup("Runs") {
            DetailRow("Session") {
                val attached = r.sessionId
                if (attached == null) Text("A new Session each run")
                else Button(onClick = { actions.onOpenSession(attached.value) }, style = ButtonStyle.Link) { Text("Continues one Session") }
            }
            DetailRow("Approvals") { Picker(RoutineApprovals.entries, r.approvals, { it.label() }, { actions.onApprovals(r.id, it) }, hint = { it.hint() }) }
            DetailRow("Last run") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                    Text(r.lastRunAt?.let { relative(it, ui.now) } ?: "Never")
                    when {
                        r.lastStatus == RunStatus.Silent && r.heartbeat -> Pill("Skipped")
                        r.lastStatus == RunStatus.Silent -> Unit
                        else -> r.lastStatus?.let { Pill(it.label(), tone = it.tone()) }
                    }
                }
            }
            r.lastError?.let { DetailRow("") { Text(it, color = color(if (r.lastStatus == RunStatus.Error) destructiveColor else mutedColor), style = TextStyles.secondary) } }
        }
        DetailGroup(if (r.heartbeat) "Checklist (HEARTBEAT.md)" else "Prompt") {
            if (r.prompt.isBlank()) Text("Empty. Edit it here, or ask the agent to add items to its Heartbeat checklist.", color = color(mutedColor), style = TextStyles.secondary)
            else Markdown(r.prompt, Modifier.fillMaxWidth())
        }
        // A Routine that ran but whose Sessions are gone says nothing rather than "No runs yet".
        if (ui.state.runs.isNotEmpty() || r.lastRunAt == null || r.sessionId != null) RowGroup("Recent runs") {
            if (ui.state.runs.isEmpty()) GroupRow(if (r.sessionId != null) "Runs continue the attached Session." else "No runs yet.", tint = color(mutedColor))
            ui.state.runs.forEach { session ->
                GroupRow(session.id.value.toRunLabel(), { actions.onOpenSession(session.id.value) }, icon = Lucide.MessageSquare) {
                    if (session.status != SessionStatus.Idle) Pill(if (session.status == SessionStatus.Running) "Running" else "Waiting", tone = Tone.Accent)
                }
            }
        }
        if (!r.heartbeat) RowGroup {
            ConfirmRow("Delete routine", Lucide.Trash2, "Delete ${r.name}?", { actions.onRemove(r.id) }, "Delete", detail = "It stops running; its past Sessions stay.")
        }
    }
}

private enum class ScheduleKind(val label: String, val placeholder: String, val hint: String) {
    Every("Every", "30m", "An interval: 30m, 2h, 1d"),
    Cron("Custom (cron)", "0 9 * * MON-FRI", "minute hour day month weekday"),
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
    DetailPane(p.back, r?.let { "Edit ${it.name}" } ?: "New routine", error = p.ui.error, trailing = { FormActions(if (r == null) "Create routine" else "Save", ready, p.ui.state.saved, onSave = save, onCancel = p.cancel, dirty = formChanged(listOf(name.text.toString(), prompt.text.toString(), kind, schedule.text.toString(), zone.text.toString(), hours.text.toString(), approvals), p.ui.state.saved)) }) {
        FormField("Name") { Input(name, "Morning brief", capitalize = true) }
        FormField(if (r?.heartbeat == true) "Checklist (HEARTBEAT.md)" else "Prompt", "Runs with only the agent's instructions and Memory, so make it self-contained. If it needs you, it notifies you and waits.") {
            TextArea(prompt, "Summarize my unread email and list anything urgent.")
        }
        FormField("Schedule", kind.hint) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Picker(ScheduleKind.entries, kind, { it.label }, { kind = it })
                Box(Modifier.weight(1f)) { Input(schedule, kind.placeholder) }
            }
        }
        // Defaults suit most Routines; open already when this one changed them.
        val custom = zone.value().isNotEmpty() || hours.value().isNotEmpty() || approvals != RoutineApprovals.Inherit
        AdvancedGroup(summary = "Time zone, active hours, Approvals", initiallyOpen = custom) {
            GroupBox {
                FormField("Time zone", "IANA name, e.g. Europe/Dublin. Blank for yours.") { Input(zone, "Europe/Dublin") }
                FormField("Active hours", "Only fire between these local times. Blank for any time.") { Input(hours, "08:00-22:00") }
                FormField("Approvals") { Picker(RoutineApprovals.entries, approvals, { it.label() }, { approvals = it }, hint = { it.hint() }) }
            }
        }
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

// A Routine that's off says so with its pill; the summary keeps to the schedule.
internal fun Routine.summary(now: Long) = trigger.describe() + when {
    !enabled && trigger is Trigger.Once && lastRunAt != null -> " · done"
    !enabled -> ""
    else -> " · " + (nextRunAt?.let { "next ${relative(it, now).removePrefix("in ")}".replace(' ', '\u00A0') } ?: "not scheduled")
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
/** A run's Session ID (yyyyMMdd-HHmm…) as a date people read, e.g. "Sun 20 Sep, 09:00". */
private fun String.toRunLabel(): String {
    if (length < 15 || this[8] != '-') return this
    val date = runCatching { kotlinx.datetime.LocalDate(substring(0, 4).toInt(), substring(4, 6).toInt(), substring(6, 8).toInt()) }.getOrNull() ?: return this
    val day = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
    val month = date.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
    return "$day ${date.day} $month, ${substring(9, 11)}:${substring(11, 13)}"
}

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
            f[2] == "*" && f[3] == "*" -> days(f[4])?.let { "$it at $time" } ?: "$time on ${f[4]}"
            else -> "Cron $expr"
        }
    }
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

private val DAY_NAMES = listOf("Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays")

/** A cron day-of-week field in words, e.g. "1-5" is weekdays and "5" Fridays; null for anything else. */
private fun days(field: String): String? = when (field.uppercase()) {
    "1-5", "MON-FRI" -> "Weekdays"
    "0,6", "6,0", "SAT,SUN" -> "Weekends"
    else -> field.toIntOrNull()?.takeIf { it in 0..7 }?.let { DAY_NAMES[it % 7] }
}
