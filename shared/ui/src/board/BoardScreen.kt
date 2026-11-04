package kinuvo.ui.board

import kinuvo.app.connection.*
import kinuvo.app.board.*
import kinuvo.app.navigation.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import com.composeunstyled.LocalTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kotlinx.coroutines.launch

@Composable
fun BoardScreen(app: AppViewModel, board: BoardViewModel, onOpenSession: (Route.Chat) -> Unit, onSettings: () -> Unit, onOpenTask: (String) -> Unit = {}) {
    val appState by app.state.collectAsState()
    val state by board.state.collectAsState()
    BoardContent(
        BoardUi(state, appState.agents, (appState.connection as? Connection.Ready)?.server?.label),
        BoardActions(
            onRefresh = board::refresh,
            onShowDone = board::showDone,
            onAdding = board::adding,
            onAdd = board::add,
            onUpdate = board::update,
            onOpenSession = { agentId, sessionId -> onOpenSession(Route.Chat(agentId, sessionId)) },
            onSettings = onSettings,
            onOpenTask = onOpenTask,
        ),
    )
}

data class BoardUi(val state: BoardState, val agents: List<AgentInfo> = emptyList(), val server: String? = null) {
    val tasks get() = state.board.tasks
    val error get() = state.errors.lastOrNull() ?: state.board.error
}

class BoardActions(
    val onRefresh: () -> Unit = {},
    val onShowDone: (Boolean) -> Unit = {},
    val onAdding: (Boolean) -> Unit = {},
    val onAdd: (CreateTaskParams) -> Unit = {},
    val onUpdate: (UpdateTaskParams) -> Unit = {},
    val onOpenSession: (agentId: String, sessionId: String) -> Unit = { _, _ -> },
    val onSettings: () -> Unit = {},
    /** Opens a Task with all its runs. */
    val onOpenTask: (taskId: String) -> Unit = {},
)

/** Kanban columns of the user's Tasks, scrolling sideways when they don't fit. */
@Composable
fun BoardContent(ui: BoardUi, actions: BoardActions) {
    // Phones page through columns with tabs, so Done is just another tab, and live updates stand in for Refresh.
    val touch = LocalInteractionMode.current == InteractionMode.Touch
    val columns = TaskStatus.entries.filter { it != TaskStatus.Done || ui.state.showDone || touch }
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        PageHeader(Lucide.SquareKanban, "Board", ui.server, actions = listOfNotNull(
            PageAction(if (ui.state.showDone) Lucide.EyeOff else Lucide.Eye, if (ui.state.showDone) "Hide done" else "Show done") { actions.onShowDone(!ui.state.showDone) }.takeUnless { touch },
            PageAction(Lucide.RefreshCw, "Refresh", actions.onRefresh).takeUnless { touch },
            PageAction(Lucide.Settings, "Task settings", actions.onSettings),
            PageAction(Lucide.Plus, "New task") { actions.onAdding(true) },
        ))
        ui.error?.let { Box(Modifier.padding(horizontal = 16.dp)) { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) } }
        if (ui.state.adding) Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { NewTaskForm(ui, actions) }
        val tasks = ui.tasks
        if (tasks == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
        else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Phones show one full column at a time, snapping, picked or swiped to with the status tabs.
            val phone = maxWidth < 600.dp
            val columnWidth = if (phone) maxWidth - 24.dp else 280.dp
            val list = rememberLazyListState()
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize()) {
            if (phone) Segmented(columns.map { it.tab() }, list.firstVisibleItemIndex, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { i ->
                scope.launch { list.animateScrollToItem(i) }
            }
            LazyRow(Modifier.weight(1f).fillMaxWidth(), list, PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                flingBehavior = if (phone) rememberSnapFlingBehavior(list) else ScrollableDefaults.flingBehavior()) {
                items(columns, key = { it }) { status ->
                    BoardColumn(status, tasks.filter { it.status == status }, ui, actions, Modifier.width(columnWidth), header = !phone)
                }
            }
            }
        }
    }
}

@Composable
private fun BoardColumn(status: TaskStatus, tasks: List<TaskInfo>, ui: BoardUi, actions: BoardActions, modifier: Modifier, header: Boolean) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header) Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(status.icon(), Modifier.size(14.dp), tint = status.tone().color())
            Text(status.label(), fontWeight = FontWeight.Normal)
            Text("${tasks.size}", color = color(mutedColor), style = TextStyles.secondary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Soonest due first, undated last.
            items(tasks.sortedWith(compareBy(nullsLast()) { it.due }), key = { it.id }) { TaskCard(it, ui.agents, actions) }
            if (tasks.isEmpty()) item {
                Text(status.empty(), Modifier.padding(horizontal = 4.dp), color = color(mutedColor), style = TextStyles.secondary)
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskInfo, agents: List<AgentInfo>, actions: BoardActions) {
    val assignee = agents.firstOrNull { it.id == task.assignee }
    Panel(Modifier.fillMaxWidth(), shape = Theme[shapes][largeShape], contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Its own target, so the card's pickers aren't buttons inside a button.
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(Theme[shapes][smallShape]).clickable(onClickLabel = "Open task", role = Role.Button) { actions.onOpenTask(task.id) },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, fontWeight = FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = LocalTextStyle.current.copy(textDecoration = if (task.status == TaskStatus.Done) TextDecoration.LineThrough else null))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(listOfNotNull(task.listName, task.due?.let { "due ${dueLabel(it)}" }).joinToString(" · "), Modifier.weight(1f, fill = false), style = TextStyles.secondary, color = color(mutedColor), singleLine = true)
                        if (task.status != TaskStatus.Done && task.due?.let(::overdue) == true) Pill("Overdue", tone = Tone.Danger)
                    }
                }
                Icon(Lucide.ChevronRight, Modifier.size(16.dp), tint = color(mutedColor))
            }
            if (task.notes.isNotBlank()) {
                Text(task.notes.trim(), color = color(mutedColor), style = TextStyles.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            // One row: a long assignee shortens rather than pushing the run link onto a line of its own.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Picker(listOf<AgentInfo?>(null) + agents, assignee, { it?.name ?: "Unassigned" }, { a ->
                    actions.onUpdate(UpdateTaskParams(task.id, assignee = a?.id ?: ""))
                }, icon = Lucide.User, modifier = Modifier.weight(1f, fill = false).widthIn(max = 160.dp))
                // Phones show the status as the tab; it changes from the Task itself.
                if (LocalInteractionMode.current != InteractionMode.Touch)
                    Picker(TaskStatus.entries, task.status, { it.label() }, { s -> actions.onUpdate(UpdateTaskParams(task.id, status = s)) }, icon = task.status.icon())
                val last = task.sessionIds.lastOrNull()
                if (last != null && task.assignee != null) {
                    Button(onClick = { actions.onOpenSession(task.assignee!!, last) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(accentColor)) {
                        Icon(Lucide.MessageSquare, Modifier.size(14.dp))
                        Text("Open run", style = TextStyles.secondary)
                        if (task.sessionIds.size > 1) Text("${task.sessionIds.size} runs", style = TextStyles.caption, color = color(mutedColor))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewTaskForm(ui: BoardUi, actions: BoardActions) {
    val title = rememberTextFieldState()
    val due = rememberTextFieldState()
    val lists = ui.state.board.lists
    var list by remember(lists) { mutableStateOf(lists.firstOrNull()) }
    var agent by remember { mutableStateOf<AgentInfo?>(null) }
    Panel(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = Theme[shapes][largeShape], contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField("Task", "Mention an agent with @id to assign it.") { Input(title, "Renew passport", capitalize = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                FormField("List") { Picker(lists, list, { it?.name ?: "Default" }, { list = it }) }
                FormField("Agent") { Picker(listOf<AgentInfo?>(null) + ui.agents, agent, { it?.name ?: "From the list" }, { agent = it }) }
                Box(Modifier.weight(1f)) { FormField("Due") { Input(due, "2026-11-20") } }
            }
            FormButtons("Add task", title.value().isNotEmpty(), null, onSave = {
                actions.onAdd(CreateTaskParams(title.value(), list?.id, due = due.value().ifEmpty { null }, assignee = agent?.id))
            }, onCancel = { actions.onAdding(false) })
        }
    }
}

internal fun TaskStatus.label() = when (this) {
    TaskStatus.Todo -> "To do"
    TaskStatus.InProgress -> "In progress"
    TaskStatus.Waiting -> "Waiting on you"
    TaskStatus.Done -> "Done"
}

private fun TaskStatus.tab() = if (this == TaskStatus.Waiting) "Waiting" else label()

private fun TaskStatus.empty() = when (this) {
    TaskStatus.Todo -> "Nothing to do. Add a task, or assign a list to an agent in Settings."
    TaskStatus.InProgress -> "No agent is working on a task."
    TaskStatus.Waiting -> "Nothing needs you."
    TaskStatus.Done -> "Nothing done yet."
}

internal fun TaskStatus.icon() = when (this) {
    TaskStatus.Todo -> Lucide.Circle
    TaskStatus.InProgress -> Lucide.LoaderCircle
    TaskStatus.Waiting -> Lucide.Hand
    TaskStatus.Done -> Lucide.CircleCheck
}

private fun TaskStatus.tone() = when (this) {
    TaskStatus.Todo -> Tone.Neutral
    TaskStatus.InProgress -> Tone.Accent
    TaskStatus.Waiting -> Tone.Warning
    TaskStatus.Done -> Tone.Success
}

