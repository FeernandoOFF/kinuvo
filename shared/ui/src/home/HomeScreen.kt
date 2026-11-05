package kinuvo.ui.home

import kinuvo.ui.navigation.*
import kinuvo.app.connection.*
import kinuvo.app.home.*
import kinuvo.app.navigation.*
import kinuvo.ui.agent.*
import kinuvo.ui.settings.*
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
import androidx.compose.ui.text.TextStyle
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
            PageHeader(Lucide.Inbox, "Today", listOfNotNull(ui.today.date.long().takeIf { isWide }, ui.server).joinToString(" · ").ifEmpty { null })
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // Empty sections are left out; the summary already says what isn't happening.
                    val t = ui.today
                    val due = !ui.tasks || t.due?.isEmpty() == true && ui.error == null
                    val clear = t.needsYou.isEmpty() && t.running.isEmpty() && t.next.isEmpty() && due && t.results.isEmpty()
                    if (clear) EmptyState(Lucide.CircleCheck, "All clear", ui.summary())
                    else Text(ui.summary(), style = TextStyles.headline, lineHeight = 28.sp, modifier = Modifier.semantics { heading() })
                    if (t.needsYou.isNotEmpty()) NeedsYou(ui, actions)
                    if (t.running.isNotEmpty()) RunningNow(ui, actions)
                    if (t.next.isNotEmpty() && !due && twoColumns) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.weight(1f)) { NextUp(ui, actions) }
                        Box(Modifier.weight(1f)) { DueThisWeek(ui, actions) }
                    } else {
                        if (t.next.isNotEmpty()) NextUp(ui, actions)
                        if (!due) DueThisWeek(ui, actions)
                    }
                    if (t.results.isNotEmpty()) Results(ui, actions)
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
                is Attention.Waiting -> AttentionCard(ui.agent(a.session.agentId), a.session.title, a.session.kind(), "Open Session") {
                    actions.onOpenSession(a.session.agentId, a.session.id)
                }
                is Attention.TaskWaiting -> AttentionCard(ui.agent(a.task.assignee), a.task.title, "Task · ${a.task.listName}", "Open Task") { actions.onOpenTask(a.task.id) }
            }
        }
    }
}

/** Something waiting on the user: who and what kind, then its title; the whole card opens it. */
@Composable
private fun AttentionCard(agent: AgentInfo?, title: String, kind: String, open: String, onOpen: () -> Unit) {
    val shape = Theme[shapes][largeShape]
    Column(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(shape).background(color(panelColor), shape).border(1.dp, Tone.Warning.color().copy(alpha = 0.45f), shape)
            .clickable(onClickLabel = open, role = Role.Button, onClick = onOpen).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentAvatar(agent, 22.dp)
            MetaLine(agent?.name ?: "Unassigned", kind, Modifier.weight(1f), TextStyles.secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Icon(Lucide.ChevronRight, Modifier.size(16.dp), tint = color(mutedColor))
        }
    }
}

@Composable
private fun RunningNow(ui: HomeUi, actions: HomeActions) {
    val running = ui.today.running
    Section(Lucide.Activity, "Running now", running.size.takeIf { it > 0 }) {
        running.forEach { r ->
            val agent = ui.agent(r.agentId)
            val kind = r.session?.kind() ?: "Routine run"
            HomeRow(agent, r.title, kind, { actions.onOpenSession(r.agentId, r.sessionId) }, lead = agent?.name) { Spinner(Modifier.semantics { contentDescription = "Running" }, size = 12.dp, tint = color(accentColor)) }
        }
    }
}

@Composable
private fun NextUp(ui: HomeUi, actions: HomeActions) {
    val next = ui.today.next
    Section(Lucide.AlarmClock, "Next up") {
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
                HomeRow(agent, d.task.title, "${ui.dayLabel(d.date)} · ${d.task.listName} · ${d.task.status.label()}", { actions.onOpenTask(d.task.id) },
                    icon = Lucide.SquareKanban.takeIf { agent == null }, flag = "Overdue".takeIf { d.overdue })
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
                    val failed = r.lastStatus == RunStatus.Error
                    val outcome = when (r.lastStatus) {
                        RunStatus.Error -> r.lastError
                        RunStatus.Stopped -> "Stopped"
                        else -> r.lastReport?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: "Reported"
                    }
                    HomeRow(agent, r.name, listOfNotNull(agent?.name ?: r.agentId, relative(r.lastRunAt ?: ui.now, ui.now), outcome).joinToString(" · "),
                        { actions.onOpenSession(r.agentId, r.lastSessionId!!) }, flag = "Failed".takeIf { failed })
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
            Text(title, fontWeight = FontWeight.Bold, style = TextStyles.label)
            count?.let { Text("$it", style = TextStyles.secondary, color = color(mutedColor)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun SubLabel(text: String) =
    Text(text.uppercase(), Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp), style = TextStyles.overline, letterSpacing = 0.6.sp, color = color(mutedColor))

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(start = 23.dp, top = 2.dp), style = TextStyles.label, color = color(mutedColor))

/** A row opening one Session, Task or Routine: [agent]'s avatar (or [icon]), a title and a muted line. */
@Composable
private fun HomeRow(agent: AgentInfo?, title: String, subtitle: String, onClick: () -> Unit, icon: ImageVector? = null, flag: String? = null, lead: String? = null,
    trailing: @Composable RowScope.() -> Unit = {}) {
    // A fixed leading slot, so rows with an icon and rows with an avatar start their text together.
    ListRow(false, onClick, leading = { Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { if (icon != null) Icon(icon, Modifier.size(16.dp)) else AgentAvatar(agent, 22.dp) } }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // A flag leads the meta line, so it doesn't take width from the title.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                flag?.let { Text(it, style = TextStyles.caption, color = Tone.Danger.color(), fontWeight = FontWeight.Medium) }
                if (lead != null) MetaLine(lead, subtitle, Modifier.weight(1f), TextStyles.caption)
                else Text(subtitle, Modifier.weight(1f), style = TextStyles.caption, color = color(mutedColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (icon != null && agent != null) AgentAvatar(agent, 20.dp)
        trailing()
    }
}

/** “[lead] · [rest]” on one line, shortening only [lead] (e.g. a long Agent name) so [rest] always shows. */
@Composable
private fun MetaLine(lead: String, rest: String, modifier: Modifier, style: TextStyle) = Row(modifier) {
    Text(lead, Modifier.weight(1f, fill = false), style = style, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
    Text(" · $rest", style = style, color = color(mutedColor), singleLine = true)
}

private fun SessionInfo.kind() = when {
    task != null -> "Task run"
    routine != null -> "Routine run"
    else -> "Session"
}

/** “2 things need you and 3 Sessions are running.” Next up lists the runs; only an empty schedule is said here. */
internal fun HomeUi.summary(): String {
    val t = today
    val needs = when (t.needsYou.size) { 0 -> "Nothing needs you"; 1 -> "1 thing needs you"; else -> "${t.needsYou.size} things need you" }
    val running = when (t.running.size) { 0 -> "no Session is running"; 1 -> "1 Session is running"; else -> "${t.running.size} Sessions are running" }
    return if (t.next.isEmpty()) "$needs, $running and no run is scheduled." else "$needs and $running."
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
