package kinuvo.ui.knowledge

import androidx.compose.foundation.lazy.itemsIndexed
import kinuvo.app.connection.*
import kinuvo.app.knowledge.*
import kinuvo.app.navigation.*
import kinuvo.ui.session.*
import kinuvo.ui.settings.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
import kinuvo.protocol.agent.*
import kinuvo.protocol.device.*
import kinuvo.protocol.knowledge.*
import kinuvo.ui.design.*
import kotlinx.coroutines.delay
import kotlin.time.Clock
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.LocalDate

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
        KnowledgeUi(state, appState.agents, now, (appState.connection as? Connection.Ready)?.server?.label),
        KnowledgeActions(onBack = onBack, onSearch = knowledge::search, onFilter = knowledge::filter, onOpen = { onOpen(Route.Knowledge(it)) }, onUndo = knowledge::undo, onSettings = onSettings),
    )
}

/** What the Knowledge screen shows; [now] (epoch ms) anchors relative times. */
data class KnowledgeUi(val state: KnowledgeState, val agents: List<AgentInfo> = emptyList(), val now: Long = 0, val server: String? = null) {
    val error get() = state.errors.lastOrNull()
}

class KnowledgeActions(
    val onBack: (() -> Unit)? = null,
    val onSearch: (String) -> Unit = {},
    /** Opens a note by ref. */
    val onOpen: (String) -> Unit = {},
    val onUndo: (String) -> Unit = {},
    /** Shows one Agent's changes, or every Agent's for null. */
    val onFilter: (AgentId?) -> Unit = {},
    val onSettings: () -> Unit = {},
)

/** Search and the latest changes beside the open note ≥900dp; narrower, the open note takes the screen. */
@Composable
fun KnowledgeContent(ui: KnowledgeUi, actions: KnowledgeActions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val wide = maxWidth >= 900.dp
        val open = ui.state.route.note
        if (wide) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(380.dp).fillMaxHeight().background(color(sidebarColor))) { Overview(ui, actions) }
            VerticalSeparator()
            Box(Modifier.weight(1f)) {
                if (open != null) NotePane(ui, actions, back = null)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Search, or pick a change to see what an agent saved.", color = color(mutedColor))
                }
            }
        } else if (open != null) NotePane(ui, actions, back = actions.onBack)
        else Overview(ui, actions)
    }
}

@Composable
private fun Overview(ui: KnowledgeUi, actions: KnowledgeActions) {
    val query = rememberTextFieldState(ui.state.query)
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { if (it != ui.state.query) actions.onSearch(it) } }
    Column(Modifier.fillMaxSize()) {
        PageHeader(Lucide.BookOpen, "Knowledge", ui.server, scope = AgentScope(ui.agents, ui.state.agent, "All agents", actions.onFilter).takeIf { ui.agents.size > 1 },
            actions = listOf(PageAction(Lucide.Settings, "Knowledge settings", actions.onSettings)))
        Column(Modifier.padding(horizontal = Spacing.space16).padding(top = Spacing.space8), verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Input(query, "Search people, projects, places…", capitalize = true, leading = { Icon(Lucide.Search, Modifier.size(16.dp), tint = color(mutedColor)) },
                trailing = if (query.text.isEmpty()) null else ({ FieldButton(Lucide.X, "Clear search", { query.clearText() }) }))
            ui.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = Spacing.space16, vertical = Spacing.space8), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (ui.state.query.isNotBlank()) {
                item { SectionLabel("Results", Modifier.padding(start = Spacing.space6)) }
                itemsIndexed(ui.state.results, key = { _, n -> "r/${n.ref}" }) { i, n ->
                    Box(Modifier.groupItem(i, ui.state.results.size)) {
                        GroupRow(n.title, { actions.onOpen(n.ref) }, n.type.replaceFirstChar { it.uppercase() }, n.type.icon(), selected = n.ref == ui.state.route.note)
                    }
                }
                if (ui.state.results.isEmpty()) item { Muted("No matches.") }
            }
            val changes = ui.state.shownChanges
            // While searching, an empty or loading history would only push the results' context away.
            if (!changes.isNullOrEmpty() || ui.state.query.isBlank()) item { SectionLabel("Recent changes", Modifier.padding(start = Spacing.space6, top = Spacing.space12)) }
            if (changes == null) { if (ui.state.query.isBlank()) item { Box(Modifier.fillMaxWidth().padding(Spacing.space12), contentAlignment = Alignment.Center) { Spinner() } } }
            else if (changes.isEmpty()) { if (ui.state.query.isBlank()) item { EmptyState(Lucide.Library, "No changes yet", if (ui.state.agent != null) "This Agent hasn't changed anything yet." else "Agents save what they learn as you talk.") } }
            else itemsIndexed(changes, key = { _, c -> "c/${c.id}" }) { i, c -> Box(Modifier.groupItem(i, changes.size)) { ChangeRow(c, ui, actions) } }
        }
    }
}

@Composable
private fun ChangeRow(change: KnowledgeChange, ui: KnowledgeUi, actions: KnowledgeActions) {
    val agent = ui.agents.firstOrNull { it.id == change.agentId }?.name ?: change.agentId
    GroupRow(change.summary, { actions.onOpen(change.ref) }, listOfNotNull(agent, relative(change.at, ui.now)).joinToString(" · "),
        selected = change.ref == ui.state.route.note, chevron = false, titleLines = 2, tint = if (change.undone) color(mutedColor) else Color.Unspecified) {
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
        Pill(n.type.replaceFirstChar { it.uppercase() }, Modifier.padding(start = Spacing.space16), icon = n.type.icon())
        if (n.properties.isNotEmpty()) DetailGroup("Properties") {
            val uri = LocalUriHandler.current
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                n.properties.forEach { (k, v) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space12)) {
                        Text(k.replaceFirstChar { it.uppercase() }, Modifier.width(96.dp), style = TextStyles.secondary, color = color(mutedColor))
                        val link = v.takeIf { URL.matches(it) }
                        if (link != null) Text(v, Modifier.weight(1f).clickable(onClickLabel = "Open link", role = Role.Button) { uri.openUri(if (link.startsWith("http")) link else "https://$link") },
                            style = TextStyles.secondary, color = color(accentColor))
                        else Text(dateLabel(v), Modifier.weight(1f), style = TextStyles.secondary)
                    }
                }
            }
        }
        if (n.body.isNotBlank()) DetailGroup("Note") {
            // Links to other notes open them in place; any other link opens as usual.
            val outer = LocalUriHandler.current
            val notes = remember(outer, actions) { object : UriHandler {
                override fun openUri(uri: String) = if (uri.startsWith(NOTE_LINK)) actions.onOpen(uri.removePrefix(NOTE_LINK).decodeRef()) else outer.openUri(uri)
            } }
            CompositionLocalProvider(LocalUriHandler provides notes) {
                Markdown(WIKILINK.replace(n.body) { m -> "[${m.groupValues[2].ifEmpty { m.groupValues[1] }}]($NOTE_LINK${m.groupValues[1].encodeRef()})" })
            }
        }
        if (links.isNotEmpty()) RowGroup("Linked notes") {
            links.forEach { (ref, label) -> GroupRow(label.replaceFirstChar { it.uppercase() }, { actions.onOpen(ref) }, icon = Lucide.FileText) }
        }
    }
}

private val URL = Regex("""^(https?://)?[\w-]+(\.[\w-]+)*\.[a-z]{2,}(/\S*)?$""")

@Composable
private fun Muted(text: String) = Text(text, Modifier.padding(horizontal = Spacing.space10, vertical = Spacing.space6), color = color(mutedColor), style = TextStyles.secondary)

private val WIKILINK = Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]""")
private const val NOTE_LINK = "kinuvo-note:"
private fun String.encodeRef() = replace("%", "%25").replace(" ", "%20").replace("(", "%28").replace(")", "%29")
private fun String.decodeRef() = replace("%29", ")").replace("%28", "(").replace("%20", " ").replace("%25", "%")

private fun String.icon() = when (this) {
    "person", "profile", "human" -> Lucide.User
    "project" -> Lucide.FolderKanban
    "organization" -> Lucide.Building2
    "place" -> Lucide.MapPin
    "event" -> Lucide.CalendarDays
    else -> Lucide.BookOpen
}

/** An ISO date value as "1 Mar 2025"; anything else as written. */
private fun dateLabel(value: String): String = runCatching {
    val d = LocalDate.parse(value)
    "${d.day} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.year}"
}.getOrDefault(value)
