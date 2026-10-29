package kinuvo.ui.home

import kinuvo.ui.navigation.*
import kinuvo.app.connection.*
import kinuvo.app.home.*
import kinuvo.app.navigation.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.session.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun HomeScreen(app: AppViewModel, home: HomeViewModel, actions: HomeActions, wide: Boolean) {
    val appState by app.state.collectAsState()
    val state by home.state.collectAsState()
    LaunchedEffect(home) {
        while (true) {
            delay(30_000)
            home.tick()
        }
    }
    HomeContent(
        HomeUi(state.today, appState.agents, (appState.connection as? Connection.Ready)?.server?.label, Clock.System.now().toEpochMilliseconds(),
            TimeZone.currentSystemDefault(), state.tasks, state.error),
        actions, wide,
    )
}

/** What Today shows; [now] (epoch ms) anchors relative times, [server] names the Server it's about. */
data class HomeUi(
    val today: Today,
    val agents: List<AgentInfo> = emptyList(),
    val server: String? = null,
    val now: Long = 0,
    val zone: TimeZone = TimeZone.UTC,
    val tasks: Boolean = false,
    val error: String? = null,
) {
    fun agent(id: String?) = agents.firstOrNull { it.id == id }
}

class HomeActions(
    val onOpenSession: (agentId: String, sessionId: String) -> Unit = { _, _ -> },
    val onOpenTask: (taskId: String) -> Unit = {},
    val onBoard: () -> Unit = {},
    val onRoutine: (agentId: String, routineId: String) -> Unit = { _, _ -> },
)

/**
 * Today, attention first: one line of status, then what needs the user, what runs now, the next Routine runs, Tasks
 * due this week and new results. Each row opens its exact Session, Task or Routine.
 */
@Composable
fun HomeContent(ui: HomeUi, actions: HomeActions, wide: Boolean? = null) {
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val isWide = wide ?: (maxWidth >= 720.dp)
        val twoColumns = maxWidth >= 860.dp
        Column(Modifier.fillMaxSize()) {
            PageHeader(Lucide.House, "Home", listOfNotNull("Today", ui.today.date.long().takeIf { isWide }, ui.server).joinToString(" · "))
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(ui.summary(), fontSize = 20.sp, lineHeight = 28.sp, modifier = Modifier.semantics { heading() })
                    NeedsYou(ui, actions)
                    RunningNow(ui, actions)
                    if (twoColumns) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.weight(1f)) { NextUp(ui, actions) }
                        Box(Modifier.weight(1f)) { DueThisWeek(ui, actions) }
                    } else {
                        NextUp(ui, actions)
                        DueThisWeek(ui, actions)
                    }
                    Results(ui, actions)
                }
            }
        }
    }
}

@Composable
private fun NeedsYou(ui: HomeUi, actions: HomeActions) {
    val items = ui.today.needsYou
    Section(Lucide.ShieldAlert, "Needs you", items.size.takeIf { it > 0 }) {
        if (items.isEmpty()) Empty("Nothing is waiting on you.")
        items.forEach { a ->
            when (a) {
                is Attention.Waiting -> AttentionCard(ui.agent(a.session.agentId), a.session.title, a.session.kind(), "Approval or answer waiting in this Session") {
                    Button({ actions.onOpenSession(a.session.agentId, a.session.id) }, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) {
                        Icon(Lucide.Hash, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Open Session")
                    }
                }
                is Attention.TaskWaiting -> AttentionCard(ui.agent(a.task.assignee), a.task.title, "Task · ${a.task.listName}", "The Task waits on you") {
                    Button({ actions.onOpenTask(a.task.id) }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) {
                        Icon(Lucide.SquareKanban, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Open Task")
                    }
                }
            }
        }
    }
}

@Composable
private fun AttentionCard(agent: AgentInfo?, title: String, kind: String, detail: String, action: @Composable RowScope.() -> Unit) {
    val shape = Theme[shapes][largeShape]
    Column(
        Modifier.fillMaxWidth().clip(shape).background(color(panelColor), shape).border(1.dp, Tone.Warning.color().copy(alpha = 0.45f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentAvatar(agent, 22.dp)
            Text(agent?.name ?: "Unassigned", fontWeight = FontWeight.Bold, singleLine = true)
            Text("· $title", Modifier.weight(1f), color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
            Pill("Waiting", tone = Tone.Warning)
        }
        Text("$kind · $detail", fontSize = 13.sp, color = color(mutedColor))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = action)
    }
}

@Composable
private fun RunningNow(ui: HomeUi, actions: HomeActions) {
    val running = ui.today.running
    Section(Lucide.Activity, "Running now", running.size.takeIf { it > 0 }) {
        if (running.isEmpty()) Empty("No Session is running.")
        running.forEach { r ->
            val agent = ui.agent(r.agentId)
            val kind = r.session?.kind() ?: "Routine run"
            HomeRow(agent, r.title, listOfNotNull(agent?.name, kind).joinToString(" · "), { actions.onOpenSession(r.agentId, r.sessionId) }) { Spinner(size = 12.dp) }
        }
    }
}

@Composable
private fun NextUp(ui: HomeUi, actions: HomeActions) {
    val next = ui.today.next
    Section(Lucide.AlarmClock, "Next up") {
        if (next.isEmpty()) Empty("No Routine is scheduled.")
        next.groupBy { it.day }.forEach { (day, runs) ->
            SubLabel(ui.dayLabel(day))
            runs.forEach { u ->
                val agent = ui.agent(u.routine.agentId)
                HomeRow(agent, u.routine.name, "${ui.time(u.at)} · ${u.routine.trigger.describe()} · ${agent?.name ?: u.routine.agentId}",
                    { actions.onRoutine(u.routine.agentId, u.routine.id) }, icon = Lucide.Circle)
            }
        }
    }
}

@Composable
private fun DueThisWeek(ui: HomeUi, actions: HomeActions) {
    val due = ui.today.due
    Section(Lucide.SquareKanban, "Due this week") {
        when {
            !ui.tasks -> Empty("This Server keeps no Tasks.")
            ui.error != null && due.isNullOrEmpty() -> Empty("Couldn't load Tasks: ${ui.error}")
            due == null -> Spinner()
            due.isEmpty() -> Empty("Nothing is due this week.")
            else -> due.forEach { d ->
                val agent = ui.agent(d.task.assignee)
                val when_ = if (d.overdue) "overdue since ${ui.dayLabel(d.date)}" else "due ${ui.dayLabel(d.date)}"
                HomeRow(agent, d.task.title, "$when_ · ${d.task.listName} · ${d.task.status.label()}", { actions.onOpenTask(d.task.id) },
                    icon = Lucide.SquareKanban.takeIf { agent == null }) {
                    if (agent == null) Text("Unassigned", fontSize = 12.sp, color = color(mutedColor))
                    if (d.overdue) Pill("Overdue", tone = Tone.Danger)
                }
            }
        }
    }
}

@Composable
private fun Results(ui: HomeUi, actions: HomeActions) {
    val results = ui.today.results
    Section(Lucide.Sparkles, "New results") {
        if (results.isEmpty()) Empty("No new results in the last two days.")
        results.forEach { o ->
            when (o) {
                is Outcome.RoutineRun -> {
                    val r = o.routine
                    val agent = ui.agent(r.agentId)
                    val outcome = when (r.lastStatus) {
                        RunStatus.Error -> "Failed" + (r.lastError?.let { ": $it" } ?: "")
                        RunStatus.Stopped -> "Stopped"
                        else -> r.lastReport?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: "Reported"
                    }
                    HomeRow(agent, r.name, "${agent?.name ?: r.agentId} · ${relative(r.lastRunAt ?: ui.now, ui.now)} · $outcome",
                        { actions.onOpenSession(r.agentId, r.lastSessionId!!) }) {
                        if (r.lastStatus == RunStatus.Error) Icon(Lucide.CircleAlert, Modifier.size(14.dp), tint = Tone.Danger.color(), contentDescription = "Failed")
                    }
                }
                is Outcome.Finished -> {
                    val agent = ui.agent(o.session.agentId)
                    HomeRow(agent, o.session.title, "${agent?.name ?: o.session.agentId} · Finished while you were away", { actions.onOpenSession(o.session.agentId, o.session.id) }) {
                        Icon(Lucide.Check, Modifier.size(14.dp), tint = color(successColor), contentDescription = "Finished")
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, count: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, Modifier.size(15.dp), tint = color(mutedColor))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            count?.let { Text("$it", fontSize = 13.sp, color = color(mutedColor)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun SubLabel(text: String) =
    Text(text.uppercase(), Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp), fontSize = 11.sp, letterSpacing = 0.6.sp, color = color(mutedColor))

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 13.sp, color = color(mutedColor))

/** A row opening one Session, Task or Routine: [agent]'s avatar (or [icon]), a title and a muted line. */
@Composable
private fun HomeRow(agent: AgentInfo?, title: String, subtitle: String, onClick: () -> Unit, icon: ImageVector? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    ListRow(false, onClick, leading = { if (icon != null) Icon(icon, Modifier.size(12.dp)) else AgentAvatar(agent, 22.dp) }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, singleLine = true, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
        }
        if (icon != null && agent != null) AgentAvatar(agent, 20.dp)
        trailing()
    }
}

private fun SessionInfo.kind() = when {
    task != null -> "Task run"
    routine != null -> "Routine run"
    else -> "Session"
}

/** “2 things need you, 3 Sessions are running and the next run is Evening journal at 21:00.” */
internal fun HomeUi.summary(): String {
    val t = today
    val needs = when (t.needsYou.size) { 0 -> "Nothing needs you"; 1 -> "1 thing needs you"; else -> "${t.needsYou.size} things need you" }
    val running = when (t.running.size) { 0 -> "no Session is running"; 1 -> "1 Session is running"; else -> "${t.running.size} Sessions are running" }
    val next = t.next.firstOrNull()?.let { "the next run is ${it.routine.name} ${if (it.day == t.date) "at" else dayLabel(it.day).let { d -> "$d at" }} ${time(it.at)}" }
        ?: "no run is scheduled"
    return "$needs, $running and $next."
}

private fun HomeUi.time(at: Long) = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).let { "${pad(it.hour)}:${pad(it.minute)}" }

private fun HomeUi.dayLabel(day: LocalDate) = when (day) {
    today.date -> "Today"
    today.date.plus(DatePeriod(days = 1)) -> "Tomorrow"
    else -> "${day.dayOfWeek.short()} ${day.day} ${day.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
}

private fun LocalDate.long() = "${dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} $day ${month.name.lowercase().replaceFirstChar { it.uppercase() }}"

private fun DayOfWeek.short() = name.take(3).lowercase().replaceFirstChar { it.uppercase() }

private fun pad(n: Int) = n.toString().padStart(2, '0')
