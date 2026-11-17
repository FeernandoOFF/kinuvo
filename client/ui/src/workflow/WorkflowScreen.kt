package kinuvo.ui.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
        state.waiting.forEach { step -> key(step.id.value) {
            WaitingBar(step, onOpen = state.sessionOf(step.id, state.activeRun)?.let { (agent, session) -> { onOpenSession(agent, session) } }) { allow -> model.approve(step.id, allow) }
        } }
        val edit = model::edit
        val settings = @Composable { modifier: Modifier ->
            Column(modifier.verticalScroll(rememberScrollState()).padding(Spacing.space16), verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
                key(generation) {
                    WorkflowSettings(workflow, agents, edit)
                    RunPanel(state, saved, running, onInput = model::input, onRun = model::run, onRetry = model::retry)
                }
                RunHistory(state.runs, state.run?.id, model::view)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth < 720.dp) Column(Modifier.fillMaxSize()) {
                // The graph gets the whole screen until the settings are asked for; a new Workflow opens with them, for its name.
                var open by rememberSaveable(workflow.id.value) { mutableStateOf(workflow.blank) }
                WorkflowEditor(model, agents, onOpenSession, Modifier.fillMaxWidth().weight(1f))
                HorizontalSeparator()
                Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = Spacing.space16, vertical = Spacing.space10),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                    Text("Settings and runs", Modifier.weight(1f), style = TextStyles.secondary, color = color(mutedColor))
                    Icon(if (open) Lucide.ChevronDown else Lucide.ChevronUp, Modifier.size(16.dp), tint = color(mutedColor), contentDescription = if (open) "Hide settings" else "Show settings")
                }
                if (open) settings(Modifier.fillMaxWidth().weight(1f))
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
    val description = synced(workflow.description) { onChange(current.copy(description = it)) }
    FormField("Description", "What this Workflow is for. Agents read it when they pick a Workflow to run.") { Input(description, "Optional", capitalize = true) }
    InputsEditor(workflow.inputs) { onChange(current.copy(inputs = it)) }
}

/** The values a run starts with: a name steps read as `{{input.name}}` and the value used when a run gives none. */
@Composable
private fun InputsEditor(inputs: List<WorkflowInput>, onChange: (List<WorkflowInput>) -> Unit) {
    val current by rememberUpdatedState(inputs)
    // Rows are keyed by position, so adding or removing one must rebuild their fields.
    var revision by remember { mutableIntStateOf(0) }
    FormField("Inputs", "Values a run starts with. A step reads one as {{input.name}}; a scheduled run uses the defaults.") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            inputs.forEachIndexed { index, input -> key(index, revision) {
                fun change(to: WorkflowInput) = onChange(current.mapIndexed { i, old -> if (i == index) to else old })
                val name = synced(input.name) { text -> change(current[index].copy(name = text.trim())) }
                val default = synced(input.default) { text -> change(current[index].copy(default = text)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                    Input(name, "name", Modifier.weight(2f), compact = true, error = !InputName.matches(input.name) || inputs.count { it.name == input.name } > 1)
                    Input(default, "default", Modifier.weight(3f), compact = true)
                    Button(onClick = { revision++; onChange(current.filterIndexed { i, _ -> i != index }) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) {
                        Icon(Lucide.X, Modifier.size(14.dp), contentDescription = "Remove input ${input.name}")
                    }
                }
            } }
            Button(onClick = { revision++; onChange(current + WorkflowInput(freeName(current))) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) {
                Icon(Lucide.Plus, Modifier.size(14.dp)); Text("Add input")
            }
        }
    }
}

private val InputName = Regex("[a-zA-Z0-9_-]+")
private fun freeName(inputs: List<WorkflowInput>) = generateSequence(1) { it + 1 }.map { "input-$it" }.first { name -> inputs.none { it.name == name } }

/** Starts a run with a value for each input, and shows how the selected run ended. */
@Composable
private fun RunPanel(state: WorkflowState, saved: Workflow?, running: Boolean, onInput: (String, String) -> Unit, onRun: () -> Unit, onRetry: () -> Unit) {
    Text("Run", style = TextStyles.title, fontWeight = FontWeight.Bold)
    val inputs = saved?.inputs.orEmpty()
    inputs.forEach { input -> key(input.name) {
        val value = synced(state.inputValues[input.name] ?: input.default) { onInput(input.name, it) }
        FormField(input.name, input.description.takeIf { it.isNotBlank() }) { Input(value, input.default.ifBlank { "Value" }) }
    } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Button(onClick = onRun, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small, enabled = saved != null && !state.dirty && !running && !state.saving) {
            Text(if (running) "Running…" else if (inputs.isEmpty()) "Run" else "Run with these")
        }
        if (state.canRetry && !state.dirty) Button(onClick = onRetry, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small, enabled = !state.saving) { Text("Retry failed steps") }
    }
    if (state.dirty) Text("Save your changes to run them.", style = TextStyles.caption, color = color(mutedColor))
    val run = state.run ?: return
    val failed = run.workflow.steps.firstOrNull { run.steps[it.id]?.status == StepStatus.Failed }
    val error = failed?.let { run.steps[it.id]?.error }
    when {
        failed != null -> FormField("Failed at ${failed.id}") { CodeBlock(error ?: "No error message", error = true, previewChars = 1200) }
        !run.output.isNullOrBlank() -> FormField("Result") { CodeBlock(run.output.orEmpty(), previewChars = 1200) }
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
                Text(run.status.label.replaceFirstChar { it.uppercase() } + if (run.retryOf != null) " · retry" else "", Modifier.weight(1f))
                run.endedAt?.let { Text(duration(it - run.startedAt), style = TextStyles.caption, color = color(mutedColor)) }
                Text(relative(run.startedAt, now), style = TextStyles.caption, color = color(mutedColor))
            }
        }
    }
}

/** A step the run waits on, reached without finding it in the graph: an Approval is answered here, a Review or a tool's Approval in its Session. */
@Composable
private fun WaitingBar(step: WorkflowStep, onOpen: (() -> Unit)?, onAnswer: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(color(warningColor).copy(alpha = 0.12f)).padding(horizontal = Spacing.space12, vertical = Spacing.space6),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Icon(if (step is WorkflowStep.Review) Lucide.ListChecks else Lucide.Hand, Modifier.size(16.dp), tint = color(warningColor))
        val text = when (step) {
            is WorkflowStep.Approval -> step.text.ifBlank { "Continue the run?" }
            is WorkflowStep.Review -> "Review: ${step.text.ifBlank { "check the data to continue" }}"
            else -> "${step.id} needs your Approval"
        }
        Text(text, Modifier.weight(1f), style = TextStyles.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (step is WorkflowStep.Approval) {
            Button(onClick = { onAnswer(false) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(destructiveColor)) { Text("Deny") }
            Button(onClick = { onAnswer(true) }, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text("Allow") }
        } else if (onOpen != null) Button(onClick = onOpen, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text(if (step is WorkflowStep.Review) "Review" else "Open") }
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

private fun duration(millis: Long) = when {
    millis < 1000 -> "${millis} ms"
    millis < 60_000 -> "${millis / 1000} s"
    else -> "${millis / 60_000} min"
}

private val WorkflowRunStatus.label get() = when (this) {
    WorkflowRunStatus.Running -> "running"; WorkflowRunStatus.Done -> "done"; WorkflowRunStatus.Failed -> "failed"; WorkflowRunStatus.Cancelled -> "cancelled"
}
