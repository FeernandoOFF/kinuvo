package kinuvo.ui.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.workflow.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.workflow.*
import kinuvo.ui.design.*
import kinuvo.ui.navigation.PageAction
import kinuvo.ui.navigation.PageHeader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kinuvo.ui.settings.relative
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** This Server's Workflows for a page that lists them beside something else, e.g. Routines: [onOpen] opens one's graph. */
class WorkflowsSection(
    val workflows: List<Workflow>?, val onOpen: (WorkflowId) -> Unit,
    /** Creates a Workflow, then hands its ID to the callback to open it. */
    val onNew: ((onCreated: (WorkflowId) -> Unit) -> Unit)?, val error: String? = null, val onReload: () -> Unit = {},
    /** The [id] Workflow's graph beside the list, like an open Routine; [onClose] goes back to the list, e.g. once it's deleted. */
    val detail: (@Composable (id: WorkflowId, onBack: (() -> Unit)?, onClose: () -> Unit) -> Unit)? = null,
)

/** The Routines page's item for the [id] Workflow, so it opens beside the list like a Routine. */
fun workflowItem(id: WorkflowId) = "$WorkflowItemPrefix${id.value}"

fun workflowOf(item: String?) = item?.takeIf { it.startsWith(WorkflowItemPrefix) }?.let { WorkflowId(it.removePrefix(WorkflowItemPrefix)) }

private const val WorkflowItemPrefix = "workflow:"

val LocalWorkflows = compositionLocalOf<WorkflowsSection?> { null }

/** The Workflows on this Server, each opening its graph. */
@Composable
fun WorkflowsScreen(workflows: List<Workflow>, agents: List<AgentInfo>, onOpen: (WorkflowId) -> Unit, onNew: () -> Unit, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        PageHeader(Lucide.Workflow, "Workflows", actions = listOfNotNull(
            PageAction(Lucide.Plus, "New Workflow", onNew),
        ), back = onBack)
        if (workflows.isEmpty()) {
            EmptyState(Lucide.Workflow, "No Workflows yet", "Chain Agent turns and Approvals into a graph the Server runs for you, on demand or on a schedule.", Modifier.fillMaxSize()) {
                Button(onClick = onNew, style = ButtonStyle.Primary, enabled = agents.isNotEmpty()) { Text("New Workflow") }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.space12), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(workflows.sortedByDescending { it.createdAt }, key = { it.id.value }) { workflow ->
                ListRow(false, { onOpen(workflow.id) }, leading = { Icon(Lucide.Workflow, Modifier.size(16.dp)) }) {
                    Column(Modifier.weight(1f)) {
                        Text(workflow.name, singleLine = true, overflow = TextOverflow.Ellipsis)
                        Text(summary(workflow), style = TextStyles.caption, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
                    }
                    if (!workflow.enabled) Pill("Off")
                }
            }
        }
    }
}

/** A Workflow to edit and run: its graph in the web editor, the rest of its settings and its runs beside it. */
@Composable
fun WorkflowScreen(model: WorkflowViewModel, agents: List<AgentInfo>, onOpenSession: (AgentId, SessionId) -> Unit, onDelete: () -> Unit, onBack: (() -> Unit)? = null) {
    val state by model.state.collectAsState()
    val saved = state.workflow
    val generation = state.generation
    var confirmDelete by remember { mutableStateOf(false) }
    val workflow = state.draft
    val run = state.activeRun ?: state.latestRun
    val running = state.activeRun != null
    val dirty = state.dirty
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        PageHeader(Lucide.Workflow, workflow?.name ?: "Workflow", run?.let { "Last run: ${it.status.label}" } ?: workflow?.let(::summary), actions = listOfNotNull(
            if (running) PageAction(Lucide.Square, "Stop run", model::cancel)
            else PageAction(Lucide.Play, "Run now") { if (!dirty) model.run() }.takeIf { !dirty && saved != null },
            PageAction(Lucide.Trash2, "Delete Workflow") { confirmDelete = true }.takeIf { saved != null && !running },
        ), back = onBack)
        if (workflow == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { state.error?.let { ErrorMessage(it) } ?: Spinner() }
            return@Column
        }
        ConfirmPopover(confirmDelete, "Delete ${workflow.name}?", "Its run history goes with it.", "Delete", true, { confirmDelete = false }) {
            confirmDelete = false; onDelete()
        }
        if (dirty) UnsavedBar(state.saving, onDiscard = model::discard, onSave = model::save)
        state.error?.let { ErrorMessage(it, Modifier.fillMaxWidth().padding(horizontal = Spacing.space12, vertical = Spacing.space6)) }
        val waiting = workflow.steps.filterIsInstance<WorkflowStep.Approval>().filter { state.activeRun?.steps?.get(it.id)?.status == StepStatus.WaitingApproval }
        waiting.forEach { approval -> key(approval.id.value) { WaitingBar(approval) { allow -> model.approve(approval.id, allow) } } }
        val edit = model::edit
        val settings = @Composable { modifier: Modifier ->
            Column(modifier.verticalScroll(rememberScrollState()).padding(Spacing.space16), verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
                key(generation) { WorkflowSettings(workflow, agents, edit) }
                RunHistory(state.runs, state.run?.id, model::view)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth < 720.dp) Column(Modifier.fillMaxSize()) {
                WorkflowEditor(model, agents, onOpenSession, Modifier.fillMaxWidth().weight(3f))
                HorizontalSeparator()
                settings(Modifier.fillMaxWidth().weight(2f))
            } else Row(Modifier.fillMaxSize()) {
                WorkflowEditor(model, agents, onOpenSession, Modifier.weight(1f).fillMaxHeight())
                VerticalSeparator()
                settings(Modifier.width(340.dp).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun UnsavedBar(saving: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(color(subtleColor)).padding(horizontal = Spacing.space12, vertical = Spacing.space6),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Text("Unsaved changes", Modifier.weight(1f), style = TextStyles.secondary, color = color(mutedColor))
        Button(onClick = onDiscard, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, enabled = !saving) { Text("Discard") }
        Button(onClick = onSave, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small, enabled = !saving) { Text(if (saving) "Saving…" else "Save") }
    }
}

@Composable
private fun WorkflowSettings(workflow: Workflow, agents: List<AgentInfo>, onChange: (Workflow) -> Unit) {
    val current by rememberUpdatedState(workflow)
    Text("Workflow", style = TextStyles.title, fontWeight = FontWeight.Bold)
    val name = synced(workflow.name) { onChange(current.copy(name = it)) }
    FormField("Name") { Input(name, "Name", capitalize = true) }
    val kind = when (workflow.trigger) { null -> 0; is Trigger.Every -> 1; is Trigger.Cron -> 2; is Trigger.Once -> 0 }
    FormField("Runs", "Manual Workflows run when you press Run.") {
        Segmented(listOf("Manually", "Every…", "Cron"), kind) { index ->
            val zone = current.timeZone.takeIf { it != "UTC" } ?: TimeZone.currentSystemDefault().id
            onChange(current.copy(trigger = when (index) { 1 -> Trigger.Every(60); 2 -> Trigger.Cron("0 9 * * *"); else -> null }, timeZone = zone))
        }
    }
    when (val trigger = workflow.trigger) {
        is Trigger.Every -> {
            val minutes = synced(trigger.minutes.toString()) { text -> text.toLongOrNull()?.takeIf { it > 0 }?.let { onChange(current.copy(trigger = Trigger.Every(it))) } }
            FormField("Every (minutes)") { Input(minutes, "60") }
        }
        is Trigger.Cron -> {
            val expr = synced(trigger.expr) { onChange(current.copy(trigger = Trigger.Cron(it.trim()))) }
            FormField("Cron", "minute hour day-of-month month day-of-week, in ${workflow.timeZone}") { Input(expr, "0 9 * * *") }
        }
        else -> {}
    }
    if (workflow.trigger != null) SwitchRow("Scheduled runs on", workflow.enabled, { onChange(current.copy(enabled = it)) })
    FormField("Runs as", "The Agent Tool and Model steps run under.") {
        Picker(listOf<AgentInfo?>(null) + agents, agents.firstOrNull { it.id == workflow.agentId }, { it?.name ?: "Not set" }, { onChange(current.copy(agentId = it?.id)) })
    }
}

@Composable
private fun RunHistory(runs: List<WorkflowRun>, shown: WorkflowRunId?, onView: (WorkflowRunId) -> Unit) {
    val now = remember(runs) { Clock.System.now().toEpochMilliseconds() }
    Text("Run history", style = TextStyles.title, fontWeight = FontWeight.Bold)
    if (runs.isEmpty()) Text("No runs yet. Pick one to see its result on the graph.", style = TextStyles.caption, color = color(mutedColor))
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        runs.take(10).forEach { run ->
            ListRow(run.id == shown, { onView(run.id) }) {
                Text(run.status.label.replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                Text(relative(run.startedAt, now), style = TextStyles.caption, color = color(mutedColor))
            }
        }
    }
}

/** An Approval step the run waits on, answered without finding it in the graph. */
@Composable
private fun WaitingBar(step: WorkflowStep.Approval, onAnswer: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(color(warningColor).copy(alpha = 0.12f)).padding(horizontal = Spacing.space12, vertical = Spacing.space6),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Icon(Lucide.Hand, Modifier.size(16.dp), tint = color(warningColor))
        Text(step.text.ifBlank { "Continue the run?" }, Modifier.weight(1f), style = TextStyles.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(onClick = { onAnswer(false) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(destructiveColor)) { Text("Deny") }
        Button(onClick = { onAnswer(true) }, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text("Allow") }
    }
}

/** A field seeded once from [initial] that reports the user's edits. */
@Composable
private fun synced(initial: String, onChange: (String) -> Unit): TextFieldState {
    val state = rememberTextFieldState(initial)
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.drop(1).distinctUntilChanged().collect { latest(it) } }
    return state
}

internal fun summary(workflow: Workflow): String {
    val schedule = when (val t = workflow.trigger) {
        null -> "Manual"
        is Trigger.Every -> "Every ${t.minutes} min"
        is Trigger.Cron -> "Cron ${t.expr}"
        is Trigger.Once -> "Once"
    }
    return "$schedule · ${workflow.steps.size} step${if (workflow.steps.size == 1) "" else "s"}"
}

private val WorkflowRunStatus.label get() = when (this) {
    WorkflowRunStatus.Running -> "running"; WorkflowRunStatus.Done -> "done"; WorkflowRunStatus.Failed -> "failed"; WorkflowRunStatus.Cancelled -> "cancelled"
}
