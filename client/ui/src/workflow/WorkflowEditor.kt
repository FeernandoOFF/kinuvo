package kinuvo.ui.workflow

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.workflow.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.session.SessionId
import kinuvo.ui.design.*
import kinuvo.ui.artifacts.BRIDGE
import kinuvo.ui.artifacts.PageChannel
import kinuvo.ui.artifacts.RECEIVE
import kinuvo.ui.artifacts.WebView
import kinuvo.ui.artifacts.framed
import kinuvo.ui.artifacts.newNonce
import kinuvo.ui.artifacts.sandboxed
import kinuvo.ui.artifacts.visualEditing
import kinuvo.ui.surface.css
import ui.generated.resources.Res

private val Parts = listOf("editor.html", "editor.css", "editor.js", "drawflow.min.js", "drawflow.min.css")

/** The editor page with its styles, script and the vendored Drawflow inlined, so it needs no network; its scripts carry [nonce]. */
internal suspend fun workflowEditorPage(nonce: String = newNonce()): String {
    val files = Parts.associateWith { Res.readBytes("files/workflow/$it").decodeToString() }
    return Regex("<!--@(\\S+?)-->").replace(files.getValue("editor.html").replace("@nonce", nonce)) { files.getValue(it.groupValues[1]) }
}

internal suspend fun workflowEditorDocument(nonce: String = newNonce()) =
    framed(sandboxed(workflowEditorPage(nonce), "'nonce-$nonce'", ""), "<script>$BRIDGE$RECEIVE</script>", "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no")

/** The Workflow graph as a web page the [model] drives; the page only edits the draft's steps and edges. */
@Composable
internal fun WorkflowEditor(model: WorkflowViewModel, agents: List<AgentInfo>, onOpenSession: (AgentId, SessionId) -> Unit, modifier: Modifier) {
    // A web view that cannot pass the page's messages on cannot edit; the web build draws on a canvas and only opens pages in a tab.
    if (!visualEditing) return Box(modifier, contentAlignment = Alignment.Center) { Text("Edit Workflows in the desktop or mobile app.", color = color(mutedColor)) }
    val state by model.state.collectAsState()
    val theme = editorTheme()
    val inputs = state.draft?.inputs.orEmpty().map { it.name }
    val catalog = remember(agents, state.models, state.tools, inputs) { EditorCatalog.of(agents, state.models, state.tools, inputs) }
    val document by produceState<String?>(null) { value = workflowEditorDocument() }
    var ready by remember { mutableStateOf(false) }
    val open by rememberUpdatedState(onOpenSession)
    val channel = remember { PageChannel() }
    val receive = remember<(String) -> Unit>(model) {
        { raw ->
            when (val event = WorkflowEditorMessages.decode(raw)) {
                EditorEvent.Ready -> ready = true
                is EditorEvent.Changed -> model.graphChanged(event.workflow)
                is EditorEvent.OpenSession -> model.state.value.sessionOf(event.step)?.let { (agent, session) -> open(agent, session) }
                null -> {}
            }
        }
    }
    fun send(command: EditorCommand) = channel.send(WorkflowEditorMessages.encode(command))
    val latest by rememberUpdatedState(state)
    LaunchedEffect(ready, state.generation) {
        val draft = latest.draft
        if (ready && draft != null) send(EditorCommand.Load(draft, catalog, theme, latest.run))
    }
    LaunchedEffect(ready, catalog) { if (ready) send(EditorCommand.CatalogChanged(catalog)) }
    LaunchedEffect(ready, state.run) { if (ready) send(EditorCommand.RunChanged(state.run)) }
    LaunchedEffect(ready, theme) { if (ready) send(EditorCommand.ThemeChanged(theme)) }
    val page = document
    if (page == null) Spinner() else WebView(page, modifier, receive, channel)
}

@Composable
private fun editorTheme(): EditorTheme {
    val dark = LocalColorScheme.current == ColorScheme.Dark
    val colors = listOf(backgroundColor, panelColor, subtleColor, onBackgroundColor, mutedColor, borderColor, accentColor, onAccentColor, successColor, warningColor, destructiveColor)
        .map { color(it).css() }
    return remember(dark, colors) { EditorTheme(dark, colors[0], colors[1], colors[2], colors[3], colors[4], colors[5], colors[6], colors[7], colors[8], colors[9], colors[10]) }
}
