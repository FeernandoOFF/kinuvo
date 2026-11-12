package kinuvo.ui.workflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.app.workflow.WorkflowViewModel
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.workflow.*
import kinuvo.ui.agent.AgentAvatar
import kinuvo.ui.design.*
import kinuvo.ui.navigation.PageAction
import kinuvo.ui.navigation.PageHeader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt

private val NodeWidth = 208.dp
private val NodeHeight = 68.dp
private const val ColumnGap = 280f
private const val RowGap = 104f

/** The Workflows on this Server, each opening its graph. */
@Composable
fun WorkflowsScreen(workflows: List<Workflow>, agents: List<AgentInfo>, onOpen: (WorkflowId) -> Unit, onNew: () -> Unit, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        PageHeader(Lucide.Workflow, "Workflows", actions = listOfNotNull(
            onBack?.let { PageAction(Lucide.ArrowLeft, "Back", it) },
            PageAction(Lucide.Plus, "New Workflow", onNew),
        ))
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

/** A Workflow's graph to edit and run: steps on a pannable canvas, the selected step's settings beside it, and the latest run's state on each step. */
@Composable
fun WorkflowScreen(model: WorkflowViewModel, agents: List<AgentInfo>, onOpenSession: (AgentId, SessionId) -> Unit, onDelete: () -> Unit, onBack: (() -> Unit)? = null) {
    val state by model.state.collectAsState()
    val saved = state.workflow
    // Bumped on discard so text fields drop their edits.
    var generation by remember { mutableIntStateOf(0) }
    var draft by remember(saved, generation) { mutableStateOf(saved) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val workflow = draft
    val run = state.activeRun ?: state.latestRun
    val running = state.activeRun != null
    val dirty = workflow != saved
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        PageHeader(Lucide.Workflow, workflow?.name ?: "Workflow", run?.let { "Last run: ${it.status.label}" } ?: workflow?.let(::summary), actions = listOfNotNull(
            onBack?.let { PageAction(Lucide.ArrowLeft, "Back", it) },
            if (running) PageAction(Lucide.Square, "Stop run", model::cancel)
            else PageAction(Lucide.Play, "Run now") { if (!dirty) model.run() }.takeIf { !dirty && saved != null },
            PageAction(Lucide.Trash2, "Delete Workflow") { confirmDelete = true }.takeIf { saved != null && !running },
        ))
        if (workflow == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { state.error?.let { ErrorMessage(it) } ?: Spinner() }
            return@Column
        }
        ConfirmPopover(confirmDelete, "Delete ${workflow.name}?", "Its run history goes with it.", "Delete", true, { confirmDelete = false }) {
            confirmDelete = false; onDelete()
        }
        if (dirty) UnsavedBar(state.saving, onDiscard = { generation++ }) { model.saveGraph(workflow) }
        state.error?.let { ErrorMessage(it, Modifier.fillMaxWidth().padding(horizontal = Spacing.space12, vertical = Spacing.space6)) }
        val steps = run?.takeIf { it.workflow.steps.map(WorkflowStep::id) == workflow.steps.map(WorkflowStep::id) || running }?.steps ?: state.steps
        val waiting = workflow.steps.filterIsInstance<WorkflowStep.Approval>().filter { steps[it.id]?.status == StepStatus.WaitingApproval }
        waiting.forEach { approval -> key(approval.id.value) { WaitingBar(approval) { allow -> model.approve(approval.id, allow) } } }
        val step = workflow.steps.firstOrNull { it.id.value == selected }
        val edit = { changed: Workflow -> draft = changed }
        val graph = @Composable { modifier: Modifier ->
            WorkflowCanvas(workflow, steps, agents, step?.id, modifier, onSelect = { selected = it?.value }, onChange = edit,
                onAdd = { kind -> val added = workflow.add(kind, agents, step); draft = added.first; selected = added.second.value })
        }
        val inspector = @Composable { modifier: Modifier ->
            Column(modifier.verticalScroll(rememberScrollState()).padding(Spacing.space16), verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
                val result = step?.let { steps[it.id] }
                if (step != null && result != null && run != null)
                    StepResult(step, result, onOpen = { id -> (step as? WorkflowStep.AgentTurn)?.let { onOpenSession(it.agentId, id) } })
                key(generation, step?.id?.value) {
                    if (step == null) WorkflowSettings(workflow, edit)
                    else StepSettings(workflow, step, agents, edit, onRemove = { draft = workflow.remove(step.id); selected = null })
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val half = maxHeight * 0.5f
            if (maxWidth < 720.dp) Column(Modifier.fillMaxSize()) {
                graph(Modifier.fillMaxWidth().height(half))
                HorizontalSeparator()
                inspector(Modifier.fillMaxWidth().weight(1f))
            } else Row(Modifier.fillMaxSize()) {
                graph(Modifier.weight(1f).fillMaxHeight())
                VerticalSeparator()
                inspector(Modifier.width(360.dp).fillMaxHeight())
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
private fun WorkflowCanvas(workflow: Workflow, steps: Map<StepId, StepRun>, agents: List<AgentInfo>, selected: StepId?, modifier: Modifier,
    onSelect: (StepId?) -> Unit, onChange: (Workflow) -> Unit, onAdd: (StepKind) -> Unit) {
    val density = LocalDensity.current.density
    var pan by remember { mutableStateOf(Offset(32f, 64f)) }
    val positions = layout(workflow)
    val latest by rememberUpdatedState(workflow)
    val edgeColor = color(borderColor)
    val doneColor = color(accentColor)
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Brings a newly selected or added step into view.
    LaunchedEffect(selected, size) {
        val p = selected?.let { positions[it] } ?: return@LaunchedEffect
        if (size == IntSize.Zero) return@LaunchedEffect
        val width = size.width / density; val height = size.height / density; val margin = 16f
        fun fit(at: Float, offset: Float, node: Float, room: Float, start: Float) = when {
            at + offset + node > room - margin -> room - margin - node - at
            at + offset < start -> start - at
            else -> offset
        }
        pan = Offset(fit(p.x, pan.x, NodeWidth.value, width, margin), fit(p.y, pan.y, NodeHeight.value, height, 56f))
    }
    Box(modifier.clipToBounds().background(color(panelColor)).onSizeChanged { size = it }
        .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); pan += drag / density } }
        .clickable(interactionSource = null, indication = null) { onSelect(null) }) {
        Canvas(Modifier.matchParentSize()) {
            val w = NodeWidth.toPx(); val h = NodeHeight.toPx()
            workflow.edges.forEach { edge ->
                val a = positions[edge.from] ?: return@forEach
                val b = positions[edge.to] ?: return@forEach
                val start = Offset((a.x + pan.x).dp.toPx() + w, (a.y + pan.y).dp.toPx() + h / 2)
                val end = Offset((b.x + pan.x).dp.toPx(), (b.y + pan.y).dp.toPx() + h / 2)
                val bend = ((end.x - start.x) / 2).coerceAtLeast(40.dp.toPx())
                val path = Path().apply { moveTo(start.x, start.y); cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y) }
                val tint = if (steps[edge.from]?.status == StepStatus.Done) doneColor else edgeColor
                drawPath(path, tint, style = Stroke(2.dp.toPx()))
                val tip = 6.dp.toPx()
                drawPath(Path().apply { moveTo(end.x, end.y); lineTo(end.x - tip, end.y - tip / 1.4f); lineTo(end.x - tip, end.y + tip / 1.4f); close() }, tint)
            }
        }
        workflow.steps.forEach { step ->
            val p = positions.getValue(step.id)
            key(step.id.value) {
                StepNode(step, steps[step.id], agents, step.id == selected,
                    Modifier.offset { IntOffset(((p.x + pan.x) * density).roundToInt(), ((p.y + pan.y) * density).roundToInt()) }
                        .pointerInput(step.id) {
                            detectDragGestures(onDragStart = { onSelect(step.id) }) { change, drag ->
                                change.consume()
                                val current = layout(latest).getValue(step.id)
                                onChange(latest.move(step.id, Position(current.x + drag.x / density, current.y + drag.y / density)))
                            }
                        },
                    onClick = { onSelect(step.id) })
            }
        }
        Row(Modifier.align(Alignment.TopStart).padding(Spacing.space8), horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            Button(onClick = { onAdd(StepKind.Agent) }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small, enabled = agents.isNotEmpty()) {
                Icon(Lucide.Bot, Modifier.size(14.dp)); Text("Agent turn", style = TextStyles.caption)
            }
            Button(onClick = { onAdd(StepKind.Approval) }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) {
                Icon(Lucide.ShieldCheck, Modifier.size(14.dp)); Text("Approval", style = TextStyles.caption)
            }
            IconButton(onClick = { pan = Offset(32f, 64f) }, style = ButtonStyle.Ghost, modifier = Modifier.size(32.dp)) {
                Icon(Lucide.Crosshair, Modifier.size(14.dp), contentDescription = "Recenter")
            }
        }
    }
}

@Composable
private fun StepNode(step: WorkflowStep, result: StepRun?, agents: List<AgentInfo>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = Theme[shapes][mediumShape]
    val border = when {
        selected -> color(accentColor)
        result?.status == StepStatus.WaitingApproval -> color(warningColor)
        result?.status == StepStatus.Failed -> color(destructiveColor)
        else -> color(borderColor)
    }
    Row(modifier.size(NodeWidth, NodeHeight).clip(shape).background(color(backgroundColor)).border(if (selected) 2.dp else 1.dp, border, shape)
        .clickable(onClick = onClick).padding(horizontal = Spacing.space10).semantics { contentDescription = "Step ${step.id.value}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        when (step) {
            is WorkflowStep.AgentTurn -> AgentAvatar(agents.firstOrNull { it.id == step.agentId }, 24.dp)
            is WorkflowStep.Approval -> Icon(Lucide.ShieldCheck, Modifier.size(20.dp), tint = color(warningColor))
        }
        Column(Modifier.weight(1f)) {
            Text(title(step, agents), style = TextStyles.secondary, fontWeight = FontWeight.Medium, singleLine = true, overflow = TextOverflow.Ellipsis)
            Text(detail(step).ifBlank { "No ${if (step is WorkflowStep.Approval) "question" else "prompt"} yet" }, style = TextStyles.caption,
                color = color(mutedColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        result?.let { StatusIcon(it.status) }
    }
}

@Composable
private fun StatusIcon(status: StepStatus) {
    when (status) {
        StepStatus.Pending -> Icon(Lucide.Circle, Modifier.size(14.dp), tint = color(mutedColor), contentDescription = "Pending")
        StepStatus.Running -> Spinner(size = 14.dp)
        StepStatus.WaitingApproval -> Icon(Lucide.Hand, Modifier.size(14.dp), tint = color(warningColor), contentDescription = "Needs you")
        StepStatus.Done -> Icon(Lucide.CircleCheck, Modifier.size(14.dp), tint = color(successColor), contentDescription = "Done")
        StepStatus.Failed -> Icon(Lucide.CircleX, Modifier.size(14.dp), tint = color(destructiveColor), contentDescription = "Failed")
        StepStatus.Skipped -> Icon(Lucide.SkipForward, Modifier.size(14.dp), tint = color(mutedColor), contentDescription = "Skipped")
        StepStatus.Cancelled -> Icon(Lucide.CircleSlash, Modifier.size(14.dp), tint = color(mutedColor), contentDescription = "Cancelled")
    }
}

@Composable
private fun WorkflowSettings(workflow: Workflow, onChange: (Workflow) -> Unit) {
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
    Text("Select a step to edit it. Drag steps to arrange them and the background to pan.", style = TextStyles.caption, color = color(mutedColor))
}

@Composable
private fun StepSettings(workflow: Workflow, step: WorkflowStep, agents: List<AgentInfo>, onChange: (Workflow) -> Unit, onRemove: () -> Unit) {
    val current by rememberUpdatedState(workflow)
    fun replace(new: WorkflowStep) = onChange(current.copy(steps = current.steps.map { if (it.id == step.id) new else it }))
    val latest = { current.steps.first { it.id == step.id } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (step is WorkflowStep.Approval) "Approval" else "Agent turn", Modifier.weight(1f), style = TextStyles.title, fontWeight = FontWeight.Bold)
        Pill(step.id.value)
    }
    when (step) {
        is WorkflowStep.AgentTurn -> {
            FormField("Agent") {
                Picker(agents, agents.firstOrNull { it.id == step.agentId } ?: agents.firstOrNull(), { it?.name ?: "No Agent" },
                    { agent -> agent?.let { replace((latest() as WorkflowStep.AgentTurn).copy(agentId = it.id)) } })
            }
            val prompt = synced(step.prompt) { replace((latest() as WorkflowStep.AgentTurn).copy(prompt = it)) }
            FormField("Prompt", "Insert an earlier step's output to pass it along.") {
                Input(prompt, "What should the Agent do?", capitalize = true, lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 12))
            }
            val earlier = ancestors(workflow, step.id)
            if (earlier.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.space6), verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                earlier.forEach { id ->
                    Button(onClick = { prompt.edit { append("{{steps.${id.value}.output}}") } }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) {
                        Icon(Lucide.Plus, Modifier.size(12.dp)); Text("${id.value} output", style = TextStyles.caption)
                    }
                }
            }
            FormField("Session") {
                Segmented(listOf("New each run", "Continue last"), step.session.ordinal) { replace((latest() as WorkflowStep.AgentTurn).copy(session = StepSession.entries[it])) }
            }
        }
        is WorkflowStep.Approval -> {
            val text = synced(step.text) { replace((latest() as WorkflowStep.Approval).copy(text = it)) }
            FormField("Question", "The run waits here until you allow it; denying ends the run.") {
                Input(text, "Publish the draft?", capitalize = true, lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6))
            }
        }
    }
    val after = workflow.edges.filter { it.to == step.id }.map { it.from }.toSet()
    val blocked = descendants(workflow, step.id) + step.id
    val candidates = workflow.steps.filter { it.id !in blocked }
    if (candidates.isNotEmpty()) FormField("Runs after", "A step starts once every step it runs after is done.") {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            candidates.forEach { other ->
                val on = other.id in after
                ListRow(on, {
                    val edges = if (on) current.edges.filterNot { it.from == other.id && it.to == step.id } else current.edges + WorkflowEdge(other.id, step.id)
                    onChange(current.copy(edges = edges))
                }) {
                    Text(title(other, agents), Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                    Text(other.id.value, style = TextStyles.caption, color = color(mutedColor))
                    if (on) Icon(Lucide.Check, Modifier.size(14.dp), tint = color(accentColor), contentDescription = "Runs after")
                }
            }
        }
    }
    if (workflow.steps.size > 1) Button(onClick = onRemove, style = ButtonStyle.Outlined, contentColor = color(destructiveColor), modifier = Modifier.fillMaxWidth()) {
        Icon(Lucide.Trash2, Modifier.size(14.dp)); Text("Remove step")
    }
}

@Composable
private fun StepResult(step: WorkflowStep, result: StepRun, onOpen: (SessionId) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        StatusIcon(result.status)
        Text("Last run: ${result.status.label}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
        result.sessionId?.takeIf { step is WorkflowStep.AgentTurn }?.let { id ->
            Button(onClick = { onOpen(id) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Open Session", style = TextStyles.caption) }
        }
    }
    result.error?.let { ErrorMessage(it) }
    result.output?.takeIf { it.isNotBlank() }?.let { Panel(Modifier.fillMaxWidth()) { Markdown(it) } }
    HorizontalSeparator()
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

internal enum class StepKind { Agent, Approval }

private fun Workflow.add(kind: StepKind, agents: List<AgentInfo>, after: WorkflowStep?): Pair<Workflow, StepId> {
    val taken = steps.map { it.id.value }.toSet()
    val prefix = if (kind == StepKind.Approval) "approval" else "step"
    val id = StepId(generateSequence(1) { it + 1 }.map { "$prefix-$it" }.first { it !in taken })
    val positions = layout(this)
    val anchor = after?.let { positions[it.id] }
    val position = anchor?.let { a ->
        val x = a.x + ColumnGap
        val used = positions.values.filter { kotlin.math.abs(it.x - x) < 1f }.maxOfOrNull { it.y }
        Position(x, if (used == null) a.y else used + RowGap)
    } ?: Position((positions.values.maxOfOrNull { it.x } ?: -ColumnGap) + ColumnGap, 0f)
    val step = when (kind) {
        StepKind.Agent -> WorkflowStep.AgentTurn(id, (after as? WorkflowStep.AgentTurn)?.agentId ?: agents.first().id, "", position = position)
        StepKind.Approval -> WorkflowStep.Approval(id, "", position)
    }
    return copy(steps = steps + step, edges = edges + listOfNotNull(after?.let { WorkflowEdge(it.id, id) })) to id
}

private fun Workflow.remove(id: StepId) = copy(steps = steps.filterNot { it.id == id }, edges = edges.filterNot { it.from == id || it.to == id })

private fun Workflow.move(id: StepId, to: Position) = copy(steps = steps.map {
    if (it.id != id) it else when (it) {
        is WorkflowStep.AgentTurn -> it.copy(position = to)
        is WorkflowStep.Approval -> it.copy(position = to)
    }
})

/** Each step's saved position, or its column by longest path from a root and its row within that column. */
internal fun layout(workflow: Workflow): Map<StepId, Position> {
    val depth = mutableMapOf<StepId, Int>()
    fun depthOf(id: StepId, seen: Set<StepId> = emptySet()): Int = depth[id] ?: (workflow.edges.filter { it.to == id && it.from !in seen }
        .maxOfOrNull { depthOf(it.from, seen + id) + 1 } ?: 0).also { depth[id] = it }
    val rows = mutableMapOf<Int, Int>()
    return workflow.steps.associate { step ->
        step.id to (step.position ?: depthOf(step.id).let { column ->
            val row = rows.getOrElse(column) { 0 }.also { rows[column] = it + 1 }
            Position(column * ColumnGap, row * RowGap)
        })
    }
}

private fun ancestors(workflow: Workflow, id: StepId): List<StepId> {
    val found = linkedSetOf<StepId>()
    fun visit(at: StepId) { workflow.edges.filter { it.to == at }.forEach { if (found.add(it.from)) visit(it.from) } }
    visit(id)
    return workflow.steps.map { it.id }.filter { it in found }
}

private fun descendants(workflow: Workflow, id: StepId): Set<StepId> {
    val found = mutableSetOf<StepId>()
    fun visit(at: StepId) { workflow.edges.filter { it.from == at }.forEach { if (found.add(it.to)) visit(it.to) } }
    visit(id)
    return found
}

private fun title(step: WorkflowStep, agents: List<AgentInfo>) = when (step) {
    is WorkflowStep.AgentTurn -> agents.firstOrNull { it.id == step.agentId }?.name ?: step.agentId.value
    is WorkflowStep.Approval -> "Approval"
}

private val outputRef = Regex("""\{\{\s*steps\.([a-zA-Z0-9_-]+)\.output\s*}}""")

private fun detail(step: WorkflowStep) = when (step) {
    is WorkflowStep.AgentTurn -> step.prompt.replace(outputRef) { "‹${it.groupValues[1]}›" }
    is WorkflowStep.Approval -> step.text
}.lineSequence().firstOrNull().orEmpty()

private fun summary(workflow: Workflow): String {
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

private val StepStatus.label get() = when (this) {
    StepStatus.Pending -> "waiting to start"; StepStatus.Running -> "running"; StepStatus.WaitingApproval -> "needs you"; StepStatus.Done -> "done"
    StepStatus.Failed -> "failed"; StepStatus.Skipped -> "skipped"; StepStatus.Cancelled -> "cancelled"
}
