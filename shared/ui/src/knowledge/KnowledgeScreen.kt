package kinuvo.ui.knowledge

import kinuvo.app.connection.*
import kinuvo.app.knowledge.*
import kinuvo.app.navigation.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Composable
fun KnowledgeScreen(app: AppViewModel, knowledge: KnowledgeViewModel, onOpen: (Route.Knowledge) -> Unit, onBack: (() -> Unit)?, onSettings: () -> Unit) {
    val appState by app.state.collectAsState()
    val state by knowledge.state.collectAsState()
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(30_000)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }
    KnowledgeContent(
        KnowledgeUi(state, appState.agents, now),
        KnowledgeActions(onBack = onBack, onSearch = knowledge::search, onOpen = { onOpen(Route.Knowledge(it)) }, onUndo = knowledge::undo, onSettings = onSettings),
    )
}

/** What the Knowledge screen shows; [now] (epoch ms) anchors relative times. */
data class KnowledgeUi(val state: KnowledgeState, val agents: List<AgentInfo> = emptyList(), val now: Long = 0) {
    val error get() = state.errors.lastOrNull()
}

class KnowledgeActions(
    val onBack: (() -> Unit)? = null,
    val onSearch: (String) -> Unit = {},
    /** Opens a note by ref. */
    val onOpen: (String) -> Unit = {},
    val onUndo: (String) -> Unit = {},
    val onSettings: () -> Unit = {},
)

/** Search and the latest changes beside the open note ≥900dp; narrower, the open note takes the screen. */
@Composable
fun KnowledgeContent(ui: KnowledgeUi, actions: KnowledgeActions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val wide = maxWidth >= 900.dp
        val open = ui.state.route.note
        if (wide) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(380.dp).fillMaxHeight().background(color(sidebarColor))) { Overview(ui, actions, back = actions.onBack) }
            VerticalSeparator()
            Box(Modifier.weight(1f)) {
                if (open != null) NotePane(ui, actions, back = null)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Search, or pick a change to see what an agent saved.", color = color(mutedColor))
                }
            }
        } else if (open != null) NotePane(ui, actions, back = actions.onBack)
        else Overview(ui, actions, back = actions.onBack)
    }
}

@Composable
private fun Overview(ui: KnowledgeUi, actions: KnowledgeActions, back: (() -> Unit)?) {
    val query = rememberTextFieldState(ui.state.query)
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { if (it != ui.state.query) actions.onSearch(it) } }
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(trailing = false) {
            back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Text("Knowledge", Modifier.padding(start = 4.dp).weight(1f), fontWeight = FontWeight.Normal)
            HeaderButton(Lucide.Settings, "Knowledge settings", actions.onSettings)
        }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Input(query, "Search people, projects, places…", capitalize = true)
            ui.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (ui.state.query.isNotBlank()) {
                item { SectionLabel("Results") }
                items(ui.state.results, key = { "r/${it.ref}" }) { n ->
                    ListRow(selected = n.ref == ui.state.route.note, onClick = { actions.onOpen(n.ref) }, leading = { Icon(n.type.icon(), Modifier.size(16.dp)) }) {
                        Text(n.title, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                        Text(n.type, color = color(mutedColor), fontSize = 12.sp)
                    }
                }
                if (ui.state.results.isEmpty()) item { Muted("No matches.") }
            }
            item { SectionLabel("Recent changes") }
            val changes = ui.state.changes
            if (changes == null) item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner() } }
            else if (changes.isEmpty()) item { Muted("Agents haven't changed anything yet. They save what they learn as you talk.") }
            else items(changes, key = { "c/${it.id}" }) { ChangeRow(it, ui, actions) }
        }
    }
}

@Composable
private fun ChangeRow(change: KnowledgeChange, ui: KnowledgeUi, actions: KnowledgeActions) {
    val agent = ui.agents.firstOrNull { it.id == change.agentId }?.name ?: change.agentId
    ListRow(selected = change.ref == ui.state.route.note, onClick = { actions.onOpen(change.ref) }) {
        Column(Modifier.weight(1f)) {
            Text(change.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (change.undone) color(mutedColor) else color(onBackgroundColor))
            Text(listOfNotNull(agent, relative(change.at, ui.now)).joinToString(" · "), color = color(mutedColor), fontSize = 12.sp)
        }
        if (change.undone) Pill("Undone")
        else Button(onClick = { actions.onUndo(change.id) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Undo") }
    }
}

@Composable
private fun NotePane(ui: KnowledgeUi, actions: KnowledgeActions, back: (() -> Unit)?) {
    val n = ui.state.note
    if (n == null) {
        DetailPane(back, "Knowledge") { if (ui.error == null) Spinner() else Pill(ui.error!!, tone = Tone.Danger, icon = Lucide.CircleX) }
        return
    }
    val links = WIKILINK.findAll(n.body).map { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[1].substringAfterLast('/') } }.distinct().toList()
    DetailPane(back, n.title) {
        Pill(n.type, icon = n.type.icon())
        if (n.properties.isNotEmpty()) DetailGroup("Properties") {
            n.properties.forEach { (k, v) -> DetailRow(k) { Text(v) } }
        }
        if (n.body.isNotBlank()) DetailGroup("Note") { Markdown(WIKILINK.replace(n.body) { m -> "*${m.groupValues[2].ifEmpty { m.groupValues[1] }}*" }) }
        if (links.isNotEmpty()) DetailGroup("Linked notes") {
            links.forEach { (ref, label) ->
                Button(onClick = { actions.onOpen(ref) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(label)
                }
            }
        }
        Text("Ref ${n.ref}", color = color(mutedColor), fontSize = 12.sp)
    }
}

@Composable
private fun Muted(text: String) = Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = color(mutedColor), fontSize = 13.sp)

private val WIKILINK = Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]""")

private fun String.icon() = when (this) {
    "person", "profile", "human" -> Lucide.User
    "project" -> Lucide.FolderKanban
    "organization" -> Lucide.Building2
    "place" -> Lucide.MapPin
    "event" -> Lucide.CalendarDays
    else -> Lucide.BookOpen
}
