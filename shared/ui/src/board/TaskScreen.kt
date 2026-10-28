package kinuvo.ui.board

import kinuvo.app.board.*
import kinuvo.app.connection.*
import kinuvo.app.navigation.*
import kinuvo.ui.agent.*
import kinuvo.ui.session.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kinuvo.protocol.*
import kinuvo.ui.design.*

/** One Task with its runs, each opening its exact Session; Back returns to wherever the Task was opened from. */
@Composable
fun TaskScreen(app: AppViewModel, board: BoardViewModel, taskId: String, onBack: (() -> Unit)?, onOpenSession: (Route.Chat) -> Unit) {
    val appState by app.state.collectAsState()
    val state by board.state.collectAsState()
    val sessions = appState.sessions.lists.values.flatMap { it.sessions }.associateBy { it.id }
    TaskContent(TaskUi(state.board.tasks?.let { all -> all.firstOrNull { it.id == taskId } }, state.board.tasks != null, appState.agents, sessions, state.errors.lastOrNull() ?: state.board.error),
        onBack, { agentId, id -> onOpenSession(Route.Chat(agentId, id)) }, board::update)
}

/** [task] is null when it isn't on the Board (yet, while [loaded] is false); [sessions] are the known Sessions by ID. */
data class TaskUi(val task: TaskInfo?, val loaded: Boolean, val agents: List<AgentInfo> = emptyList(), val sessions: Map<String, SessionInfo> = emptyMap(), val error: String? = null)

@Composable
fun TaskContent(ui: TaskUi, onBack: (() -> Unit)?, onOpenSession: (agentId: String, sessionId: String) -> Unit, onUpdate: (UpdateTaskParams) -> Unit = {}) {
    val task = ui.task
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        TitleBarRow {
            onBack?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Text(task?.title ?: "Task", Modifier.padding(start = 4.dp).weight(1f), fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
        }
        when {
            task == null && !ui.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
            task == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(ui.error ?: "This Task is no longer on the Board.", color = color(mutedColor))
            }
            else -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(task.title, fontSize = 20.sp, lineHeight = 28.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(task.listName, icon = Lucide.List)
                        task.due?.let { Pill("Due $it", icon = Lucide.Calendar) }
                        Picker(TaskStatus.entries, task.status, { it.label() }, { s -> onUpdate(UpdateTaskParams(task.id, status = s)) })
                    }
                    val assignee = ui.agents.firstOrNull { it.id == task.assignee }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentAvatar(assignee, 20.dp)
                        Text(assignee?.let { "Assigned to ${it.name}" } ?: "Unassigned", color = color(mutedColor))
                    }
                    if (task.notes.isNotBlank()) Text(task.notes.trim())
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionLabel("Runs")
                        if (task.sessionIds.isEmpty()) Text("No Agent has worked on it yet.", Modifier.padding(horizontal = 10.dp), color = color(mutedColor), fontSize = 13.sp)
                        task.sessionIds.asReversed().forEachIndexed { i, id ->
                            val session = ui.sessions[id]
                            val agentId = session?.agentId ?: task.assignee
                            ListRow(false, { agentId?.let { onOpenSession(it, id) } }, enabled = agentId != null, leading = { Icon(Lucide.SquareKanban, Modifier.size(16.dp)) }) {
                                Column(Modifier.weight(1f)) {
                                    Text(session?.title ?: "Run ${task.sessionIds.size - i}", singleLine = true, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(if (i == 0) "Latest run" else "Run ${task.sessionIds.size - i}", ui.agents.firstOrNull { it.id == agentId }?.name).joinToString(" · "),
                                        fontSize = 12.sp, color = color(mutedColor))
                                }
                                session?.let { SessionStatusIcon(it.status, false) }
                            }
                        }
                    }
                }
            }
        }
    }
}
