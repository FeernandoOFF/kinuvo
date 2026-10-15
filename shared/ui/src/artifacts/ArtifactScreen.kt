package kinuvo.ui.artifacts

import kinuvo.app.connection.*
import kinuvo.app.artifacts.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.Api
import kinuvo.protocol.ArtifactData
import kinuvo.protocol.ArtifactInfo
import kinuvo.protocol.ArtifactRef
import kinuvo.ui.design.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

class ArtifactsActions(
    val onBack: (() -> Unit)? = null,
    val onOpen: (String) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onFilter: (String?) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onOpenSession: (agentId: String, sessionId: String) -> Unit = { _, _ -> },
)

@Composable
fun ArtifactsScreen(app: AppViewModel, artifacts: ArtifactsViewModel, actions: ArtifactsActions) {
    val appState by app.state.collectAsState()
    val state by artifacts.state.collectAsState()
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(30_000)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }
    ArtifactsContent(state, appState.agents, now, actions)
}

/** The list beside the open Artifact ≥900dp; narrower, the open Artifact takes the screen. */
@Composable
fun ArtifactsContent(state: ArtifactsState, agents: List<AgentInfo>, now: Long, actions: ArtifactsActions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val open = state.route.artifact
        if (maxWidth >= 900.dp) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(380.dp).fillMaxHeight().background(color(sidebarColor))) { ArtifactList(state, agents, now, actions, actions.onBack) }
            VerticalSeparator()
            Box(Modifier.weight(1f)) {
                if (open != null) ArtifactPane(state, agents, now, actions, back = null)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pick an artifact to open it.", color = color(mutedColor)) }
            }
        } else if (open != null) ArtifactPane(state, agents, now, actions, actions.onBack)
        else ArtifactList(state, agents, now, actions, actions.onBack)
    }
}

@Composable
private fun ArtifactList(state: ArtifactsState, agents: List<AgentInfo>, now: Long, actions: ArtifactsActions, back: (() -> Unit)?) {
    val query = rememberTextFieldState(state.query)
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { if (it != state.query) actions.onSearch(it) } }
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(trailing = false) {
            back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Text("Artifacts", Modifier.padding(start = 4.dp).weight(1f), fontWeight = FontWeight.Normal)
        }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Input(query, "Search by title…", capitalize = true)
            if (agents.size > 1) Picker(listOf<String?>(null) + agents.map { it.id }, state.agent, { id -> id?.let { agents.name(it) } ?: "All agents" }, actions.onFilter)
            state.errors.lastOrNull()?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        }
        val all = state.all
        LazyColumn(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            when {
                all == null -> item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner() } }
                state.shown.isEmpty() -> item {
                    Text(
                        if (all.isEmpty()) "No artifacts yet. Ask an agent for a report, chart or page and it saves one here." else "No matches.",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = color(mutedColor), fontSize = 13.sp,
                    )
                }
                else -> items(state.shown, key = { it.id }) { a ->
                    ListRow(selected = a.id == state.route.artifact, onClick = { actions.onOpen(a.id) }, leading = { Icon(a.type.icon(), Modifier.size(16.dp)) }) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, singleLine = true, overflow = TextOverflow.Ellipsis)
                            Text("${agents.name(a.agentId)} · ${relative(a.updated, now)}", color = color(mutedColor), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** HTML and SVG render in a web view, Markdown as text, anything else as code. */
@Composable
private fun ArtifactPane(state: ArtifactsState, agents: List<AgentInfo>, now: Long, actions: ArtifactsActions, back: (() -> Unit)?) {
    val data = state.open?.takeIf { it.info.id == state.route.artifact }
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(leading = back != null) {
            back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(data?.info?.title ?: "Artifact", fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                data?.info?.let { Text("${it.type} · ${agents.name(it.agentId)} · ${relative(it.updated, now)}", fontSize = 12.sp, color = color(mutedColor), singleLine = true) }
            }
            data?.info?.let { info ->
                info.sessionId?.let { s -> HeaderButton(Lucide.MessageSquare, "Open the chat", { actions.onOpenSession(info.agentId, s) }) }
                HeaderButton(Lucide.Trash2, "Delete", { actions.onDelete(info.id) })
            }
        }
        HorizontalSeparator()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                data == null -> (if (state.missing) "This artifact was deleted." else state.errors.lastOrNull())
                    ?.let { ErrorMessage(it, Modifier.align(Alignment.Center).padding(24.dp)) } ?: Spinner(Modifier.align(Alignment.Center))
                data.info.type == "html" || data.info.type == "svg" -> key(data.info.updated) {
                    HtmlView(if (data.info.type == "svg") "<html><body style=\"margin:0\">${data.content}</body></html>" else data.content, Modifier.fillMaxSize())
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    if (data.info.type == "md") Markdown(data.content, Modifier.fillMaxWidth()) else CodeBlock(data.content, language = data.info.type)
                }
            }
        }
    }
}

private fun List<AgentInfo>.name(id: String) = firstOrNull { it.id == id }?.name ?: id

private fun String.icon(): ImageVector = when (this) {
    "html" -> Lucide.AppWindow
    "svg" -> Lucide.Image
    "csv" -> Lucide.Sheet
    "json" -> Lucide.Braces
    else -> Lucide.FileText
}
