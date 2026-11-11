package kinuvo.ui.board

import kinuvo.app.board.*
import kinuvo.app.connection.*
import kinuvo.app.navigation.*
import kinuvo.ui.settings.*
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
import kinuvo.protocol.agent.*
import kinuvo.protocol.board.*
import kinuvo.protocol.session.*
import kinuvo.ui.design.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlinx.datetime.LocalDate

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
data class TaskUi(val task: TaskInfo?, val loaded: Boolean, val agents: List<AgentInfo> = emptyList(), val sessions: Map<SessionId, SessionInfo> = emptyMap(), val error: String? = null)

@Composable
fun TaskContent(ui: TaskUi, onBack: (() -> Unit)?, onOpenSession: (agentId: AgentId, sessionId: SessionId) -> Unit, onUpdate: (UpdateTaskParams) -> Unit = {}) {
    val task = ui.task
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        // On phones the floating Back alone; the title is the page's heading below it.
        if (LocalInteractionMode.current == InteractionMode.Touch) Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.space12, vertical = Spacing.space6)) {
            onBack?.let { FloatingBubble(Lucide.ArrowLeft, "Back", it) }
        } else TitleBarRow {
            onBack?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Text(task?.title ?: "Task", Modifier.padding(start = Spacing.space4).weight(1f), fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
        }
        when {
            task == null && !ui.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
            task == null -> Box(Modifier.fillMaxSize().padding(Spacing.space24), contentAlignment = Alignment.Center) {
                Text(ui.error ?: "This Task is no longer on the Board.", color = color(mutedColor))
            }
            else -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = Spacing.space16, vertical = Spacing.space16), verticalArrangement = Arrangement.spacedBy(Spacing.space16)) {
                    Text(task.title, style = TextStyles.headline, lineHeight = 28.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.space6), verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                        Pill(task.listName, icon = Lucide.List)
                        task.due?.let { Pill("Due ${dueLabel(it)}", icon = Lucide.Calendar) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                        Text("Status", Modifier.width(FieldLabelWidth), style = TextStyles.secondary, color = color(mutedColor))
                        Picker(TaskStatus.entries, task.status, { it.label() }, { s -> onUpdate(UpdateTaskParams(task.id, status = s)) }, icon = task.status.icon())
                    }
                    val assignee = ui.agents.firstOrNull { it.id.value == task.assignee }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                        Text("Assignee", Modifier.width(FieldLabelWidth), style = TextStyles.secondary, color = color(mutedColor))
                        Picker(listOf<AgentInfo?>(null) + ui.agents, assignee, { it?.name ?: "Unassigned" }, { a -> onUpdate(UpdateTaskParams(task.id, assignee = a?.id?.value ?: "")) }, icon = Lucide.User)
                    }
                    if (task.notes.isNotBlank()) Text(task.notes.trim(), style = TextStyles.secondary)
                    // Rows carry their own inset; the list starts at the page edge so their text lines up with the title.
                    Column(Modifier.offset(x = (-10).dp), verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
                        SectionLabel("Runs")
                        if (task.sessionIds.isEmpty()) Text("No Agent has worked on it yet.", Modifier.padding(horizontal = Spacing.space10), color = color(mutedColor), style = TextStyles.secondary)
                        task.sessionIds.asReversed().forEachIndexed { i, id ->
                            val session = ui.sessions[id]
                            val agentId = session?.agentId ?: task.assignee?.let(::AgentId)
                            ListRow(false, { agentId?.let { onOpenSession(it, id) } }, enabled = agentId != null, leading = { Icon(Lucide.MessageSquare, Modifier.size(16.dp)) }) {
                                val run = if (i == 0) "Latest run" else "Run ${task.sessionIds.size - i}"
                                // Runs are usually titled after the Task, so those just say which run they are.
                                val title = session?.title?.takeIf { it != task.title }
                                Column(Modifier.weight(1f)) {
                                    Text(title ?: run, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(run.takeIf { title != null }, ui.agents.firstOrNull { it.id == agentId }?.name).joinToString(" · "),
                                        style = TextStyles.caption, color = color(mutedColor))
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

/** A Task's due date as "Mon 2 Nov", or as given when it isn't an ISO date. */
internal fun dueLabel(iso: String): String = runCatching {
    val d = LocalDate.parse(iso)
    "${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.day} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
}.getOrDefault(iso)

/** Whether an ISO due date is before today. */
internal fun overdue(iso: String): Boolean = runCatching {
    LocalDate.parse(iso) < Clock.System.todayIn(TimeZone.currentSystemDefault())
}.getOrDefault(false)

// One label column, so the pickers line up.
private val FieldLabelWidth = 84.dp
