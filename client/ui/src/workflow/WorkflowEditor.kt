package kinuvo.ui.workflow

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.workflow.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.workflow.WorkflowStep
import kinuvo.ui.design.*
import kinuvo.ui.surface.HtmlBridge
import kinuvo.ui.surface.HtmlWebView
import kinuvo.ui.surface.css
import kinuvo.ui.surface.htmlSurfaceDocument
import ui.generated.resources.Res

private val Parts = listOf("editor.html", "editor.css", "editor.js", "drawflow.min.js", "drawflow.min.css")

/** The editor page with its styles, script and the vendored Drawflow inlined, so it needs no network. */
internal suspend fun workflowEditorPage(): String {
    val files = Parts.associateWith { Res.readBytes("files/workflow/$it").decodeToString() }
    return Regex("<!--@(\\S+?)-->").replace(files.getValue("editor.html")) { files.getValue(it.groupValues[1]) }
}

internal suspend fun workflowEditorDocument() = htmlSurfaceDocument(workflowEditorPage(), true)

/** The Workflow graph as a web page the [model] drives; the page only edits the draft's steps and edges. */
@Composable
internal fun WorkflowEditor(model: WorkflowViewModel, agents: List<AgentInfo>, onOpenSession: (AgentId, SessionId) -> Unit, modifier: Modifier) {
    val state by model.state.collectAsState()
    val theme = editorTheme()
    val catalog = remember(agents, state.models, state.tools) { EditorCatalog.of(agents, state.models, state.tools) }
    val document by produceState<String?>(null) { value = workflowEditorDocument() }
    var ready by remember { mutableStateOf(false) }
    val open by rememberUpdatedState(onOpenSession)
    val bridge = remember(model) {
        HtmlBridge { raw ->
            when (val event = WorkflowEditorMessages.decode(raw)) {
                EditorEvent.Ready -> ready = true
                is EditorEvent.Changed -> model.graphChanged(event.workflow)
                is EditorEvent.OpenSession -> {
                    val current = model.state.value
                    val agent = (current.draft?.steps?.firstOrNull { it.id == event.step } as? WorkflowStep.AgentTurn)?.agentId
                    val session = current.steps[event.step]?.sessionId
                    if (agent != null && session != null) open(agent, session)
                }
                null -> {}
            }
            emptyList()
        }
    }
    fun send(command: EditorCommand) = bridge.post(WorkflowEditorMessages.encode(command))
    val latest by rememberUpdatedState(state)
    LaunchedEffect(ready, state.generation) {
        val draft = latest.draft
        if (ready && draft != null) send(EditorCommand.Load(draft, catalog, theme, latest.run))
    }
    LaunchedEffect(ready, catalog) { if (ready) send(EditorCommand.CatalogChanged(catalog)) }
    LaunchedEffect(ready, state.run) { if (ready) send(EditorCommand.RunChanged(state.run)) }
    LaunchedEffect(ready, theme) { if (ready) send(EditorCommand.ThemeChanged(theme)) }
    val page = document
    if (page == null) Spinner() else key(bridge, page) { HtmlWebView(page, bridge, modifier) }
}

@Composable
private fun editorTheme(): EditorTheme {
    val dark = LocalColorScheme.current == ColorScheme.Dark
    val colors = listOf(backgroundColor, panelColor, subtleColor, onBackgroundColor, mutedColor, borderColor, accentColor, onAccentColor, successColor, warningColor, destructiveColor)
        .map { color(it).css() }
    return remember(dark, colors) { EditorTheme(dark, colors[0], colors[1], colors[2], colors[3], colors[4], colors[5], colors[6], colors[7], colors[8], colors[9], colors[10]) }
}
