package kinuvo.ui.artifacts

import androidx.compose.foundation.lazy.itemsIndexed
import kinuvo.app.connection.*
import kinuvo.app.artifacts.*
import kinuvo.ui.session.*
import kinuvo.ui.settings.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.ui.rememberOpenInBrowser
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.protocol.artifact.ArtifactData
import kinuvo.protocol.artifact.ArtifactPermissions
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.ui.design.*
import kotlinx.coroutines.delay
import kotlin.time.Clock

class ArtifactsActions(
    val onBack: (() -> Unit)? = null,
    val onOpen: (String) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onFilter: (AgentId?) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onOpenSession: (agentId: AgentId, sessionId: SessionId) -> Unit = { _, _ -> },
    val onFullScreen: (Boolean) -> Unit = {},
    val onEdit: () -> Unit = {},
    val onChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onKeepMine: () -> Unit = {},
    val onSaveDesign: (String) -> Unit = {},
    /** Reads one file of an Artifact folder, by Artifact id and path. */
    val onReadFile: suspend (String, String) -> ByteArray? = { _, _ -> null },
    /** Grants everything the open Artifact asks for, or takes every grant back. */
    val onAllowAll: (Boolean) -> Unit = {},
    val onGrant: (ArtifactCapabilities) -> Unit = {},
    val onRevoke: (ArtifactCapabilities) -> Unit = {},
    /** In the Side panel: its header closes the tab and expands it to the Artifacts page. */
    val onClose: (() -> Unit)? = null,
    val onExpand: (() -> Unit)? = null,
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
    val deck = remember(artifacts) { DeckControls(artifacts::pageMessage, artifacts::step, artifacts.pageCommands) }
    ArtifactsContent(state, appState.agents, now, actions, deck)
}

/** The list beside the open Artifact ≥900dp; narrower, or [Route.Artifacts.fullScreen], the open Artifact takes the screen. [deck] shares a reveal.js deck in an HTML page. */
@Composable
fun ArtifactsContent(state: ArtifactsState, agents: List<AgentInfo>, now: Long, actions: ArtifactsActions, deck: DeckControls? = null) {
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val open = state.route.artifact
        if (open != null && state.route.fullScreen) ArtifactPane(state, agents, now, actions, back = null, deck)
        else if (maxWidth >= 900.dp) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(ListColumnWidth).fillMaxHeight().background(color(backgroundColor))) { ArtifactList(state, agents, now, actions) }
            VerticalSeparator()
            Box(Modifier.weight(1f)) {
                if (open != null) ArtifactPane(state, agents, now, actions, back = null, deck)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pick an artifact to open it.", color = color(mutedColor)) }
            }
        } else if (open != null) ArtifactPane(state, agents, now, actions, actions.onBack, deck)
        else ArtifactList(state, agents, now, actions)
    }
}

@Composable
private fun ArtifactList(state: ArtifactsState, agents: List<AgentInfo>, now: Long, actions: ArtifactsActions) {
    val query = rememberTextFieldState(state.query)
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { if (it != state.query) actions.onSearch(it) } }
    Column(Modifier.fillMaxSize()) {
        PageHeader(Lucide.Shapes, "Artifacts", scope = AgentScope(agents, state.agent, "All agents", actions.onFilter).takeIf { agents.size > 1 })
        Column(Modifier.padding(horizontal = Spacing.space12).padding(top = Spacing.space8), verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Input(query, "Search by title…", capitalize = true)
            state.errors.lastOrNull()?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        }
        val all = state.all
        LazyColumn(Modifier.weight(1f).padding(horizontal = Spacing.space12, vertical = Spacing.space8), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            when {
                all == null -> item { Box(Modifier.fillMaxWidth().padding(Spacing.space12), contentAlignment = Alignment.Center) { Spinner() } }
                state.shown.isEmpty() -> item {
                    Text(
                        if (all.isEmpty()) "No artifacts yet. Ask an agent for a report, chart or page and it saves one here." else "No matches.",
                        Modifier.padding(horizontal = Spacing.space10, vertical = Spacing.space6), color = color(mutedColor), style = TextStyles.secondary,
                    )
                }
                else -> itemsIndexed(state.shown, key = { _, a -> a.id }) { i, a ->
                    Box(Modifier.groupItem(i, state.shown.size)) {
                        GroupRow(a.title, { actions.onOpen(a.id) }, "${agents.name(a.agentId.value)} · ${relative(a.updated, now)}", a.type.icon(), selected = a.id == state.route.artifact, titleLines = 2)
                    }
                }
            }
        }
    }
}

/** Content fills the pane; the back button, the actions or the editing controls float over it. HTML and SVG render in a web view, Markdown as text, anything else as code. */
@Composable
private fun ArtifactPane(state: ArtifactsState, agents: List<AgentInfo>, now: Long, actions: ArtifactsActions, back: (() -> Unit)?, deck: DeckControls?) {
    val data = state.open?.takeIf { it.info.id == state.route.artifact }
    val permissions = state.permissions?.takeIf { it.id == data?.info?.id && it.asking }
    val editing = state.draft != null
    val leave = if (state.route.fullScreen && actions.onClose == null) ({ actions.onFullScreen(false) }) else null
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (leave != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BoxWithConstraints(Modifier.fillMaxSize().then(if (leave == null) Modifier else Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        (e.type == KeyEventType.KeyDown && e.key == Key.Escape).also { if (it) leave() }
    })) { CompositionLocalProvider(LocalWebEscape provides leave) {
        val wide = maxWidth >= 700.dp
        val tabs = data?.info?.editorTabs(wide, visualEditing).orEmpty()
        var chosen by remember(editing) { mutableStateOf(tabs.firstOrNull()) }
        val tab = chosen?.takeIf { it in tabs } ?: tabs.firstOrNull()
        val density = LocalDensity.current
        val design = remember(data?.content, data?.info?.type) { data?.takeIf { it.info.isDesign }?.let { designOf(it.content) } }
        var present by remember(data?.info?.id) { mutableStateOf<Int?>(null) }
        var focus by remember(data?.info?.id) { mutableStateOf<String?>(null) }
        var remote by remember(data?.info?.id) { mutableStateOf(false) }
        val shownDeck = state.deck.takeIf { deck != null && data?.info?.web == true && !editing }
        val background = color(backgroundColor)
        var controls by remember { mutableStateOf(ControlsHeight) }
        // A full-screen page, e.g. a presentation, takes the whole window; its controls fade out until the pointer reaches the top.
        val hiding = actions.onClose == null && state.route.fullScreen && !editing && data?.info?.web == true &&
            LocalInteractionMode.current != InteractionMode.Touch
        var shown by remember(hiding) { mutableStateOf(true) }
        var hovered by remember(hiding) { mutableStateOf(false) }
        LaunchedEffect(hiding, shown, hovered) { if (hiding && shown && !hovered) { delay(2500); shown = false } }
        when {
            data == null -> (if (state.missing) "This artifact was deleted." else state.errors.lastOrNull())
                ?.let { ErrorMessage(it, Modifier.align(Alignment.Center).padding(Spacing.space24)) } ?: Spinner(Modifier.align(Alignment.Center))
            editing -> ArtifactEditor(data, state, actions, tab, wide, Modifier.padding(top = controls))
            data.info.isDesign -> key(data.info.id) {
                DesignCanvas(
                    data.content, background, Modifier.fillMaxSize().padding(top = controls),
                    if (visualEditing) { id -> if (design?.artboards?.any { it.id == id } == true) focus = id } else null,
                    if (visualEditing) actions.onSaveDesign else null,
                )
            }
            data.info.web -> Box(Modifier.fillMaxSize().padding(top = if (hiding) 0.dp else controls)) {
                val remoting = remote && shownDeck != null
                val page = when {
                    !remoting -> Modifier.fillMaxSize()
                    wide -> Modifier.align(Alignment.TopStart).fillMaxHeight().padding(end = RemoteWidth)
                    else -> Modifier.align(Alignment.TopStart).fillMaxWidth().fillMaxHeight(RemotePageShare)
                }
                HtmlView(
                    data.info.page(data.content), page, fade = if (hiding) null else color(backgroundColor), deck = deck, artifact = data.info.id,
                    bundle = state.files?.takeIf { data.info.bundle && it.id == data.info.id }?.let { files -> BundleSource(files) { path -> actions.onReadFile(files.id, path) } },
                    access = ArtifactAccess.of(permissions?.effective),
                )
                if (remoting && shownDeck != null && deck != null) {
                    if (wide) Row(Modifier.align(Alignment.TopEnd).fillMaxHeight()) {
                        VerticalSeparator()
                        DeckRemote(shownDeck, deck.onStep, Modifier.width(RemoteWidth).fillMaxHeight())
                    } else Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(1 - RemotePageShare)) {
                        HorizontalSeparator()
                        DeckRemote(shownDeck, deck.onStep, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
            else -> SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = Spacing.space24, end = Spacing.space24, top = controls + Spacing.space8, bottom = Spacing.space24)) {
                if (data.info.type == "md") Markdown(data.content, Modifier.fillMaxWidth())
                else CodeBlock(data.content, language = data.info.type)
            }
        }
        if (data?.info?.isDesign == true && !editing) state.errors.lastOrNull()?.let { Pill(it, Modifier.align(Alignment.BottomCenter).padding(Spacing.space16), tone = Tone.Danger, icon = Lucide.CircleX) }
        val chrome = LocalWindowChrome.current
        val leading = back != null || state.route.fullScreen
        val editBar = @Composable {
            FloatingBar {
                Text("Cancel", Modifier.clip(CircleShape).clickable(onClick = actions.onCancel).padding(horizontal = Spacing.space10, vertical = Spacing.space8), style = TextStyles.secondary)
                if (tabs.size > 1 && tab != null) Segmented(tabs.map { it.label() }, tabs.indexOf(tab), Modifier, small = true) { chosen = tabs[it] }
                val on = state.dirty && !state.saving && !state.stale
                Text(
                    if (state.saving) "Saving…" else "Save",
                    Modifier.clip(CircleShape).background(if (on) color(accentColor) else color(subtleColor)).clickable(enabled = on, onClick = actions.onSave).padding(horizontal = Spacing.space12, vertical = Spacing.space8),
                    style = TextStyles.secondary, fontWeight = FontWeight.SemiBold, color = if (on) color(backgroundColor) else color(mutedColor),
                )
            }
        }
        val close = actions.onClose
        if (close != null) Column(Modifier.fillMaxWidth().background(background).onSizeChanged { controls = with(density) { it.height.toDp() } }) {
            TitleBarRow(leading = false) {
                // A draft is saved or cancelled before the tab can close or move.
                if (!editing) {
                    HeaderButton(Lucide.X, "Close artifact", close)
                    actions.onExpand?.let { HeaderButton(Lucide.Maximize2, "Expand artifact", it) }
                }
                if (data != null && !editing) {
                    if (design?.artboards?.isNotEmpty() == true) HeaderButton(Lucide.Play, "Present", { present = 0 })
                    if (shownDeck != null) HeaderButton(Lucide.Presentation, if (remote) "Stop presenting" else "Present from this device", { remote = !remote }, selected = remote)
                    if (!data.info.isDesign) HeaderButton(Lucide.Pencil, "Edit", actions.onEdit)
                    data.info.sessionId?.let { s -> HeaderButton(Lucide.MessageSquare, "Open the chat", { actions.onOpenSession(data.info.agentId, s) }) }
                    ArtifactMenu(data, agents, now, state.route.fullScreen, actions, permissions, state.canGrant)
                }
                Icon(data?.info?.type?.icon() ?: Lucide.Shapes, Modifier.padding(start = Spacing.space6).size(16.dp), tint = color(mutedColor))
                Text(data?.info?.title ?: "Artifact", Modifier.weight(1f).padding(start = Spacing.space4), singleLine = true, overflow = TextOverflow.Ellipsis)
                if (data != null && editing) editBar()
            }
            HorizontalSeparator()
        } else Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 8.dp).then(if (hiding) Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) when (awaitPointerEvent(PointerEventPass.Initial).type) {
                    PointerEventType.Enter, PointerEventType.Move -> { hovered = true; shown = true }
                    PointerEventType.Exit -> hovered = false
                }
            }
        } else Modifier)) { AnimatedVisibility(!hiding || shown, enter = fadeIn(), exit = fadeOut()) { Row(
            Modifier.fillMaxWidth().onSizeChanged { controls = with(density) { it.height.toDp() } }
                .padding(chrome?.padding(leading, true) ?: PaddingValues()).padding(horizontal = Spacing.space10, vertical = Spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (back != null) FloatingBar { FloatingButton(Lucide.ArrowLeft, "Back", back) }
            Spacer(Modifier.weight(1f))
            if (data != null) {
                if (editing) editBar() else FloatingBar {
                    if (design?.artboards?.isNotEmpty() == true) FloatingButton(Lucide.Play, "Present") { present = 0 }
                    if (shownDeck != null) FloatingToggle(Lucide.Presentation, if (remote) "Stop presenting" else "Present from this device", remote) { remote = !remote }
                    if (data.info.isDesign.not()) FloatingButton(Lucide.Pencil, "Edit", actions.onEdit)
                    data.info.sessionId?.let { s -> FloatingButton(Lucide.MessageSquare, "Open the chat", { actions.onOpenSession(data.info.agentId, s) }) }
                    ArtifactMenu(data, agents, now, state.route.fullScreen, actions, permissions, state.canGrant)
                }
            }
        } } }
        val shownBoard = design?.artboards?.firstOrNull { it.id == focus }
        LaunchedEffect(shownBoard == null, focus) { if (focus != null && shownBoard == null && design != null) focus = null }
        if (shownBoard != null && data != null && !editing) DesignFocus(data.content, shownBoard.id, shownBoard.name.ifBlank { shownBoard.id }, background, actions.onSaveDesign) { focus = null }
        val index = present
        if (index != null && design != null && !editing) ArtboardViewer(design.artboards, index, background, { present = it }, { present = null })
    } }
}

internal val ControlsHeight = 56.dp
private val RemoteWidth = 360.dp
private const val RemotePageShare = 0.34f
private val PointerButtonSize = 32.dp

@Composable
internal fun FloatingBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.titleBarControl().shadow(4.dp, CircleShape).background(color(backgroundColor), CircleShape).border(1.dp, color(borderColor), CircleShape).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.space2),
        content = content,
    )
}

@Composable
internal fun FloatingButton(icon: ImageVector, label: String, onClick: () -> Unit) = FloatingToggle(icon, label, false, onClick)

@Composable
internal fun FloatingToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val touch = LocalInteractionMode.current == InteractionMode.Touch
    IconButton(onClick, Modifier.size(if (touch) 40.dp else PointerButtonSize).titleBarControl(), style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(if (active) accentColor else if (touch) onBackgroundColor else mutedColor)) {
        Icon(icon, Modifier.size(17.dp), contentDescription = label)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ArtifactMenu(data: ArtifactData, agents: List<AgentInfo>, now: Long, full: Boolean, actions: ArtifactsActions, permissions: ArtifactPermissions?, canGrant: Boolean) {
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var permitting by remember { mutableStateOf(false) }
    val browser = rememberOpenInBrowser()
    val dark = LocalColorScheme.current == ColorScheme.Dark
    val clipboard = LocalClipboardManager.current
    val info = data.info
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            DropdownMenuPanel {
                Column(Modifier.padding(horizontal = Spacing.space10, vertical = Spacing.space6)) {
                    Text(info.title, fontWeight = FontWeight.SemiBold, singleLine = true, overflow = TextOverflow.Ellipsis)
                    Text("${info.type} · ${agents.name(info.agentId.value)} · ${relative(info.updated, now)}", style = TextStyles.caption, color = color(mutedColor), singleLine = true)
                }
                HorizontalSeparator()
                if (actions.onExpand == null) MenuAction(if (full) Lucide.Minimize2 else Lucide.Maximize2, if (full) "Exit full screen" else "Full screen") { open = false; actions.onFullScreen(!full) }
                if (browser != null && info.web && !info.bundle) MenuAction(Lucide.ExternalLink, "Open in browser") { open = false; browser(browserDocument(info.page(data.content), dark)) }
                if (permissions != null) MenuAction(if (permissions.pending) Lucide.ShieldAlert else Lucide.ShieldCheck, if (permissions.pending) "Permissions (asked for)" else "Permissions") { open = false; permitting = true }
                MenuAction(Lucide.Copy, "Copy source") { open = false; clipboard.setText(AnnotatedString(data.content)) }
                HorizontalSeparator()
                MenuAction(Lucide.Trash2, "Delete", destructive = true) { open = false; asking = true }
            }
        },
    ) {
        Box {
            FloatingButton(Lucide.Ellipsis, "More", { open = true })
            if (permissions?.pending == true && canGrant) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(8.dp).background(color(accentColor), CircleShape))
        }
    }
    if (permissions != null) Popover(permitting, { permitting = false }, permissionsTitle(permissions)) {
        ArtifactPermissionsPanel(permissions, canGrant, now, PermissionActions(actions.onAllowAll, actions.onGrant, actions.onRevoke))
    }
    ConfirmPopover(asking, "Delete this artifact?", info.title, "Delete", true, { asking = false }) { asking = false; actions.onDelete(info.id) }
}

@Composable
private fun DropdownMenuPanelContentScope.MenuAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) color(destructiveColor) else color(onBackgroundColor)
    DropdownMenuItem(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Icon(icon, Modifier.size(16.dp), tint = tint)
            Text(label, color = tint, singleLine = true)
        }
    }
}

private fun EditorTab.label() = when (this) {
    EditorTab.Visual -> "Visual"
    EditorTab.Source -> "Source"
    EditorTab.Preview -> "Preview"
}

/** The source beside a live preview when [wide], else one of the [tab]s at a time. */
@Composable
private fun ArtifactEditor(data: ArtifactData, state: ArtifactsState, actions: ArtifactsActions, tab: EditorTab?, wide: Boolean, modifier: Modifier = Modifier) {
    val info = data.info
    val text = state.draft?.text.orEmpty()
    val base = state.draft?.base
    var preview by remember { mutableStateOf(text) }
    LaunchedEffect(text) { delay(300); preview = text }
    val visualSource = remember(base, tab) { text }
    val editor = @Composable { key(base) { SourceEditor(text, actions.onChange, Modifier.fillMaxSize()) } }
    val view = @Composable {
        if (info.web) HtmlView(info.page(preview), Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.space24)) { Markdown(preview, Modifier.fillMaxWidth()) }
    }
    Column(modifier.fillMaxSize()) {
        if (state.stale) Row(
            Modifier.fillMaxWidth().background(color(subtleColor)).padding(horizontal = Spacing.space12, vertical = Spacing.space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
        ) {
            Text("Updated by an agent while you edit.", Modifier.weight(1f), style = TextStyles.secondary, color = color(mutedColor))
            Button(onClick = actions.onEdit, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Reload") }
            Button(onClick = actions.onKeepMine, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) { Text("Keep mine") }
        }
        state.errors.lastOrNull()?.let { Pill(it, Modifier.padding(Spacing.space8), tone = Tone.Danger, icon = Lucide.CircleX) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab == EditorTab.Visual -> key(visualSource, base) { VisualEditor(visualSource, Modifier.fillMaxSize(), actions.onChange) }
                tab == EditorTab.Preview -> view()
                info.previewable && wide -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { editor() }
                    VerticalSeparator()
                    Box(Modifier.weight(1f)) { view() }
                }
                else -> editor()
            }
        }
    }
}

@Composable
private fun SourceEditor(initial: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val text = rememberTextFieldState(initial)
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect(onChange) }
    BasicTextField(
        text,
        modifier.verticalScroll(rememberScrollState()).padding(Spacing.space12),
        textStyle = MonoStyle.copy(color = color(onBackgroundColor)),
        cursorBrush = SolidColor(color(accentColor)),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
    )
}

private fun List<AgentInfo>.name(id: String) = firstOrNull { it.id.value == id }?.name ?: id

private fun String.icon(): ImageVector = when (this) {
    "html" -> Lucide.AppWindow
    "svg" -> Lucide.Image
    "csv" -> Lucide.Sheet
    "json" -> Lucide.Braces
    "design" -> Lucide.Frame
    else -> Lucide.FileText
}
