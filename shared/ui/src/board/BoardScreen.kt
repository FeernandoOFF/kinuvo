package kinuvo.ui.board

import kinuvo.app.connection.*
import kinuvo.app.board.*
import kinuvo.app.navigation.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.protocol.*
import kinuvo.ui.design.*

@Composable
fun BoardScreen(app: AppViewModel, board: BoardViewModel, onBack: (() -> Unit)?, onOpenSession: (Route.Chat) -> Unit, onSettings: () -> Unit) {
    val appState by app.state.collectAsState()
    val state by board.state.collectAsState()
    BoardContent(
        BoardUi(state, appState.agents),
        BoardActions(
            onBack = onBack,
            onRefresh = board::refresh,
            onShowDone = board::showDone,
            onAdding = board::adding,
            onAdd = board::add,
            onUpdate = board::update,
            onOpenSession = { agentId, sessionId -> onOpenSession(Route.Chat(agentId, sessionId)) },
            onSettings = onSettings,
        ),
    )
}

data class BoardUi(val state: BoardState, val agents: List<AgentInfo> = emptyList()) {
    val tasks get() = state.board.tasks
    val error get() = state.errors.lastOrNull() ?: state.board.error
}

class BoardActions(
    val onBack: (() -> Unit)? = null,
    val onRefresh: () -> Unit = {},
    val onShowDone: (Boolean) -> Unit = {},
    val onAdding: (Boolean) -> Unit = {},
    val onAdd: (CreateTaskParams) -> Unit = {},
    val onUpdate: (UpdateTaskParams) -> Unit = {},
    val onOpenSession: (agentId: String, sessionId: String) -> Unit = { _, _ -> },
    val onSettings: () -> Unit = {},
)

/** Kanban columns of the user's Tasks, scrolling sideways when they don't fit. */
@Composable
fun BoardContent(ui: BoardUi, actions: BoardActions) {
    val columns = TaskStatus.entries.filter { it != TaskStatus.Done || ui.state.showDone }
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        TitleBarRow {
            actions.onBack?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Text("Board", Modifier.padding(start = 4.dp).weight(1f), fontWeight = FontWeight.Normal)
            Button(onClick = { actions.onShowDone(!ui.state.showDone) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) {
                Text(if (ui.state.showDone) "Hide done" else "Show done")
            }
            HeaderButton(Lucide.RefreshCw, "Refresh", actions.onRefresh)
            HeaderButton(Lucide.Settings, "Task settings", actions.onSettings)
            HeaderButton(Lucide.Plus, "New task", { actions.onAdding(true) })
        }
        ui.error?.let { Box(Modifier.padding(horizontal = 16.dp)) { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) } }
        if (ui.state.adding) Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { NewTaskForm(ui, actions) }
        val tasks = ui.tasks
        if (tasks == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
        else Row(
            Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            columns.forEach { status -> BoardColumn(status, tasks.filter { it.status == status }, ui, actions) }
        }
    }
}

@Composable
private fun BoardColumn(status: TaskStatus, tasks: List<TaskInfo>, ui: BoardUi, actions: BoardActions) {
    Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(status.icon(), Modifier.size(14.dp), tint = status.tone().color())
            Text(status.label(), fontWeight = FontWeight.Normal)
            Text("${tasks.size}", color = color(mutedColor), fontSize = 13.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { TaskCard(it, ui.agents, actions) }
            if (tasks.isEmpty()) item {
                Text(status.empty(), Modifier.padding(horizontal = 4.dp), color = color(mutedColor), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskInfo, agents: List<AgentInfo>, actions: BoardActions) {
    val assignee = agents.firstOrNull { it.id == task.assignee }
    Panel(Modifier.fillMaxWidth(), shape = Theme[shapes][largeShape], contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.title, fontWeight = FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (task.notes.isNotBlank()) {
                Text(task.notes.trim(), color = color(mutedColor), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(task.listName, icon = Lucide.List)
                task.due?.let { Pill(it, icon = Lucide.Calendar) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Picker(listOf<AgentInfo?>(null) + agents, assignee, { it?.name ?: "Nobody" }, { a ->
                    actions.onUpdate(UpdateTaskParams(task.id, assignee = a?.id ?: ""))
                })
                Spacer(Modifier.weight(1f))
                Picker(TaskStatus.entries, task.status, { it.label() }, { s -> actions.onUpdate(UpdateTaskParams(task.id, status = s)) })
            }
            val last = task.sessionIds.lastOrNull()
            if (last != null && task.assignee != null) {
                Button(onClick = { actions.onOpenSession(task.assignee!!, last) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) {
                    Icon(Lucide.MessageSquare, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (task.sessionIds.size > 1) "Open latest run (${task.sessionIds.size})" else "Open run")
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

private fun TaskStatus.empty() = when (this) {
    TaskStatus.Todo -> "Nothing to do. Add a task, or assign a list to an agent in tasks.yaml."
    TaskStatus.InProgress -> "No agent is working on a task."
    TaskStatus.Waiting -> "Nothing needs you."
    TaskStatus.Done -> "Nothing done yet."
}

private fun TaskStatus.icon() = when (this) {
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
