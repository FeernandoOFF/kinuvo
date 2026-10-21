package kinuvo.ui.session

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.navigation.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.theme.Theme
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.model.RichTextState
import kinuvo.client.ConnectionState
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.Risk
import kinuvo.protocol.AgentSettings
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionSettings
import kinuvo.protocol.SessionStatus
import kinuvo.protocol.Usage
import kinuvo.protocol.ServerFeature
import kinuvo.protocol.supports
import kinuvo.ui.design.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.builtins.ListSerializer
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.Question
import kinuvo.protocol.UploadAttachmentParams
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.draw.alpha
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val ContentWidth = 760.dp

@Composable
fun ChatScreen(
    app: AppViewModel,
    session: SessionViewModel,
    wide: Boolean,
    shell: ShellChrome,
    onOpen: (Route.Chat) -> Unit,
    onSettings: (agentId: String) -> Unit,
    onNavigate: (Route) -> Unit = {},
    onToggleShell: (() -> Unit)? = null,
) {
    val appState by app.state.collectAsState()
    val current by session.state.collectAsState()
    val route = current.route
    val connection by session.connection.collectAsState()
    val pickAttachment = rememberAttachmentPicker(session::addAttachments)
    val saveAttachment = rememberAttachmentSaver(session::attachmentError)
    val speech = getKoin().getOrNull<SpeechEngine>()
    val voice = speech?.let { koinViewModel<VoiceViewModel> { parametersOf(it, session) }.chat }
    val dictation = speech?.let { koinViewModel<DictationViewModel> { parametersOf(it) } }
    // The ViewModels outlive the screen, e.g. while Settings is open; the microphone doesn't.
    DisposableEffect(voice, dictation) { onDispose { voice?.end(); dictation?.stop() } }
    // Picking a photo takes the app out of view too; voice chat waits for it instead of ending.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { voice?.background(); dictation?.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { voice?.foreground() }
    val draft = rememberRichTextState()
    remember(draft) { draft.setMarkdown(app.draft(route.slot)) }
    DisposableEffect(draft, route.slot) { onDispose { app.saveDraft(route.slot, draft.toMarkdown()) } }
    LaunchedEffect(route.agentId, route.sessionId, current.resolving) { session.shown() }
    val state = ChatState(
        agent = appState.agents.firstOrNull { it.id == route.agentId },
        sessions = route.agentId?.let { appState.sessions.lists[it] }?.sessions.orEmpty(),
        selected = route.sessionId,
        info = current.info?.takeIf { it.id == route.sessionId },
        items = current.items,
        loaded = current.loaded,
        hasOlder = current.data?.hasOlder == true,
        running = current.running,
        completions = if (current.capabilities?.server.supports(ServerFeature.Skills)) listOf(CompletionSource('/', current.skills.filter { it.active && it.userInvocable }.map { Completion(it.name, it.description) }, startOnly = true)) else emptyList(),
        settings = current.settings,
        defaults = current.defaults,
        models = current.models,
        attachments = current.attachments,
        unsent = current.unsent,
        sending = current.sending,
        capabilities = current.capabilities,
        voice = voice?.state?.collectAsState()?.value,
        dictation = dictation?.state?.collectAsState()?.value,
        preview = current.preview?.let { id ->
            current.attachments.firstOrNull { it.id == id }?.let { AttachmentPreview(it.attachment, it.upload.base64) }
                ?: current.downloads[id]?.let { AttachmentPreview(it.attachment.copy(id = id), it.base64) }
                ?: current.items.firstNotNullOfOrNull { item -> (item as? ChatItem.User)?.attachments?.firstOrNull { it.id == id } }?.let { AttachmentPreview(it, null) }
        },
        answers = current.answers,
    )
    ChatContent(
        state, connection, appState.usage,
        ChatActions(
            onNew = { if (route.sessionId != null) onOpen(Route.Chat(route.agentId, compose = true)) },
            onSelect = { id -> if (id != route.sessionId) onOpen(Route.Chat(route.agentId, id)) },
            onSend = session::send,
            onAttach = pickAttachment,
            onDropAttachments = session::addAttachments,
            onRemoveAttachment = session::removeAttachment,
            onLoadAttachment = session::loadAttachment,
            onSaveAttachment = { session.saveAttachment(it, saveAttachment) },
            onPreviewAttachment = session::preview,
            downloads = current.downloads,
            onConfigure = session::configure,
            onRefreshModels = { session.loadModels(true) },
            onStop = session::stop,
            onDecision = { _, decision -> session.respond(decision) },
            onReviewReflection = session::reviewReflection,
            onAnswer = session::answer,
            onEditAnswer = session::editAnswer,
            onOpenArtifact = { onNavigate(Route.Artifacts(it)) },
            onWatch = { onNavigate(Route.Computers()) },
            onLoadOlder = session::loadOlder,
            onSettings = { route.agentId?.let(onSettings) },
            onRename = session::rename,
            onFavorite = session::favorite,
            onVoice = voice?.let { v -> { dictation?.stop(); v.start() } },
            onPauseVoice = { if (it) voice?.pause() else voice?.resume() },
            onHoldVoice = { if (it) voice?.hold() else voice?.letGo() },
            onSpeaker = { voice?.speaker(it) },
            onEndVoice = { voice?.end() },
            onDictate = dictation?.let { it::toggle },
            onDiscardDictation = { dictation?.discard() },
        ),
        wide = wide,
        compose = route.compose && route.sessionId == null,
        draft = draft,
        onToggleShell = onToggleShell ?: { if (wide) shell.open = !shell.open else shell.revealed = true },
    )
}

class ChatActions(
    val onNew: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onAttach: (AttachmentSource) -> Unit = {},
    val onDropAttachments: (List<UploadAttachmentParams>, String?) -> Unit = { _, _ -> },
    val onRemoveAttachment: (String) -> Unit = {},
    val onLoadAttachment: (String) -> Unit = {},
    val onSaveAttachment: (String) -> Unit = {},
    /** Opens an attachment full screen; null closes it. */
    val onPreviewAttachment: (String?) -> Unit = {},
    val downloads: Map<String, kinuvo.protocol.AttachmentData> = emptyMap(),
    val onConfigure: (SessionSettings) -> Unit = {},
    val onRefreshModels: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onDecision: (String, ApprovalDecision) -> Unit = { _, _ -> },
    val onReviewReflection: (String, Boolean) -> Unit = { _, _ -> },
    /** The open `ask_user` form's answers by question ID; null skips it. */
    val onAnswer: (Map<String, List<String>>?) -> Unit = {},
    val onEditAnswer: (questionsId: String, questionId: String, value: List<String>) -> Unit = { _, _, _ -> },
    val onOpenArtifact: (id: String) -> Unit = {},
    /** Opens Live view of the Computers an Agent controls. */
    val onWatch: () -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onFavorite: (Boolean) -> Unit = {},
    val onVoice: (() -> Unit)? = null,
    val onPauseVoice: (Boolean) -> Unit = {},
    /** Holds to talk, or lets go to send. */
    val onHoldVoice: (Boolean) -> Unit = {},
    val onSpeaker: (Boolean) -> Unit = {},
    val onEndVoice: () -> Unit = {},
    val onDictate: (() -> Unit)? = null,
    val onDiscardDictation: () -> Unit = {},
)

/** [pairing] is set when the [active] Server can pair other Devices. */
data class ServerMenuState(
    val servers: List<Server> = emptyList(),
    val active: Server? = null,
    val pairing: PairingInfo? = null,
    val pairingBase: String? = null,
    val pairingError: String? = null,
    val canPair: Boolean = false,
)

/**
 * Stateless Session screen. [wide] defaults to the width it gets; [compose] opens with the composer focused, keyboard
 * up; [onToggleShell], when set, shows or hides the shell. Draws under
 * [LocalWindowChrome] when present.
 */
@Composable
fun ChatContent(
    state: ChatState,
    connection: ConnectionState,
    usage: Usage?,
    actions: ChatActions,
    wide: Boolean? = null,
    compose: Boolean = false,
    onToggleShell: (() -> Unit)? = null,
    draft: RichTextState = rememberRichTextState(),
) {
    // Hoisted above the layout so the draft and scroll survive rotation and process death.
    val messages = rememberLazyListState()
    val follow = rememberSaveable { mutableStateOf(true) }
    val root = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var composerFocused by remember { mutableStateOf(false) }
    val composerFocus = remember { FocusRequester() }
    var completing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (compose) composerFocus.requestFocus() }

    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val wide = wide ?: (maxWidth >= 720.dp)
        val toggle = onToggleShell

        fun step(delta: Int) {
            val i = state.sessions.indexOfFirst { it.id == state.selected }
            state.sessions.getOrNull(if (i < 0) 0 else i + delta)?.let { actions.onSelect(it.id) }
        }

        fun onKey(e: KeyEvent): Boolean {
            if (e.type != KeyEventType.KeyDown) return false
            when {
                e.key == Key.Escape && completing -> return false
                // Escape leaves the composer first; unhandled, it goes back a screen.
                e.key == Key.Escape -> if (rootFocused) return false else root.requestFocus()
                e.isShortcutPressed && e.key == Key.N -> actions.onNew()
                e.isShortcutPressed && e.key == Key.Backslash && toggle != null -> toggle()
                e.isShortcutPressed && e.key == Key.L -> composerFocus.requestFocus()
                e.isShortcutPressed && e.key == Key.Comma && state.agent != null -> actions.onSettings()
                e.isShortcutPressed && e.isShiftPressed && e.key == Key.Period && state.running -> actions.onStop()
                e.isAltPressed && e.key == Key.DirectionUp -> step(-1)
                e.isAltPressed && e.key == Key.DirectionDown -> step(1)
                e.key == Key.PageUp || e.key == Key.PageDown -> scope.launch {
                    val page = messages.layoutInfo.viewportSize.height * 0.85f
                    messages.animateScrollBy(if (e.key == Key.PageUp) -page else page)
                }
                else -> return false
            }
            return true
        }

        val background = color(backgroundColor)
        val blur = remember(background) { HazeBlurStyle { blurRadius(24.dp); backgroundColor(background) } }
        val gaze = if (composerFocused) Gaze.Down else Gaze.Forward
        val haze = rememberHazeState()
        val density = LocalDensity.current
        var composerHeight by remember { mutableStateOf(0.dp) }
        Column(Modifier.fillMaxSize().onFocusChanged { rootFocused = it.isFocused }.focusRequester(root).focusable().onPreviewKeyEvent(::onKey)) {
            // Touch screens get floating bubbles; pointers, at any width, a toolbar in the title bar.
            val floating = !wide && LocalInteractionMode.current == InteractionMode.Touch
            if (!floating) TopBar(state, usage, actions) {
                HeaderControls(wide, toggle, actions.onNew.takeIf { !wide && state.selected != null })
            }
            // The composer floats over the messages, which scroll on under its blur.
            var dropping by remember { mutableStateOf(false) }
            val canDrop = !state.running && (state.files?.actionable != false || state.photos?.actionable != false)
            Box(Modifier.weight(1f).attachmentDrop(canDrop, actions.onDropAttachments) { dropping = it }) {
                val voice = state.voice?.takeIf { it.active }
                // Keeps the last state while voice chat animates away.
                var shownVoice by remember { mutableStateOf(voice) }
                voice?.let { shownVoice = it }
                CompositionLocalProvider(LocalRiveGaze provides gaze) {
                    Messages(state, messages, follow, Modifier.fillMaxSize().hazeSource(haze), actions, composerHeight,
                        top = (if (floating) FloatingBarHeight else 0.dp) + (if (voice != null) VoiceStatusHeight else 0.dp))
                }
                state.voice?.let { VoiceGlow(it, Modifier.align(Alignment.BottomCenter)) }
                androidx.compose.animation.AnimatedVisibility(dropping, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) { DropOverlay() }
                if (floating) FloatingBar(state, usage, actions, toggle, Modifier.align(Alignment.TopCenter))
                androidx.compose.animation.AnimatedVisibility(voice != null, Modifier.align(Alignment.TopCenter).padding(top = if (floating) FloatingBarHeight + 8.dp else 12.dp),
                    enter = fadeIn() + slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { -it },
                    exit = fadeOut() + slideOutVertically { -it }) {
                    shownVoice?.let { VoiceStatus(it) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                    .onFocusChanged { composerFocused = it.hasFocus }.padding(horizontal = 16.dp).padding(bottom = if (wide) 10.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LaunchedEffect(state.unsent) { state.unsent?.let { if (draft.annotatedString.text.isBlank()) draft.setMarkdown(it) } }
                    var dictated by remember { mutableStateOf<DictationDraft?>(null) }
                    LaunchedEffect(state.dictation) {
                        val dictation = state.dictation ?: return@LaunchedEffect
                        val current = dictated ?: if (dictation.dictating) DictationDraft(draft.toMarkdown().trim()) else return@LaunchedEffect
                        if (dictation.text.isNotBlank()) {
                            val next = current.next(draft.toMarkdown().trim(), dictation.text)
                            draft.setMarkdown(next.shown)
                            draft.selection = TextRange(draft.annotatedString.text.length)
                            dictated = next.copy(shown = draft.toMarkdown().trim())
                        } else dictated = current
                        if (!dictation.dictating) dictated = null
                    }
                    val settings: @Composable RowScope.() -> Unit = {
                        state.defaults?.let { ComposerSettings(state.settings, it, state.models, actions.onConfigure, actions.onAttach, !state.running, actions.onRefreshModels, state.photos, state.files) }
                    }
                    val attachments: @Composable () -> Unit = { if (state.attachments.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.attachments.forEach { a -> key(a.id) { AttachmentChip(a.attachment, a.upload.base64, onPreview = { actions.onPreviewAttachment(a.id) }, onRemove = if (state.running) null else ({ actions.onRemoveAttachment(a.id) })) } }
                    } }
                    AnimatedContent(voice != null, contentAlignment = Alignment.BottomCenter, transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 80)) + slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it / 3 } +
                            scaleIn(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.94f))
                            .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 4 })
                            .using(SizeTransform(clip = false))
                    }) { inVoice ->
                    if (inVoice) shownVoice?.let { VoiceControls(it, actions.onPauseVoice, actions.onHoldVoice, actions.onSpeaker, actions.onEndVoice, Modifier.widthIn(max = ContentWidth), settings, attachments) }
                    else Composer(
                        running = state.running,
                        onSend = { follow.value = true; actions.onDiscardDictation(); actions.onSend(it) },
                        onStop = actions.onStop,
                        placeholder = "Chat with ${state.agent?.name ?: "Kinuvo"}",
                        hint = state.dictation?.error ?: if (wide && state.items.isEmpty()) "Shift+Enter for new line · Markdown formats as you type" + if (state.skills) " · / for skills" else "" else null,
                        blocked = state.chatBlocked,
                        modifier = Modifier.widthIn(max = ContentWidth),
                        state = draft,
                        focus = composerFocus,
                        completions = state.completions,
                        onCompletingChange = { completing = it },
                        hasAttachments = state.attachments.isNotEmpty(),
                        sending = state.sending,
                        attachments = attachments,
                        controls = settings,
                        containerColor = color(fieldColor).copy(alpha = 0.7f),
                        backdrop = Modifier.hazeBlur(HazeInput.Sources(haze), blur),
                        onVoice = actions.onVoice?.let { start -> { follow.value = true; start() } },
                        onDictate = actions.onDictate,
                        dictating = state.dictation?.dictating == true,
                    )
                    }
                }
                ConnectionSnackbar(connection, Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + 12.dp).padding(horizontal = 16.dp))
            }
        }
        // Keeps the last preview while it fades away.
        var previewing by remember { mutableStateOf(state.preview) }
        state.preview?.let { previewing = it }
        androidx.compose.animation.AnimatedVisibility(state.preview != null, enter = fadeIn(), exit = fadeOut()) {
            previewing?.let { p -> AttachmentPreviewer(p, { actions.onSaveAttachment(p.attachment.id) }, { actions.onPreviewAttachment(null) }) }
        }
    }
}

/** Row at the window's top edge: as tall as the native title bar, clear of its window buttons. */
@Composable
internal fun TitleBarRow(modifier: Modifier = Modifier, leading: Boolean = true, trailing: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val chrome = LocalWindowChrome.current
    Row(
        modifier
            .fillMaxWidth()
            .height(chrome?.height ?: 52.dp)
            .padding(chrome?.padding(leading, trailing) ?: PaddingValues())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/**
 * The desktop toolbar: the shell's toggle and, narrow, a new Session. Back and forward are left to the shortcuts and
 * mouse buttons.
 */
@Composable
private fun HeaderControls(wide: Boolean, onToggle: (() -> Unit)?, onNew: (() -> Unit)?) {
    onToggle?.let { if (wide) HeaderButton(Lucide.PanelLeft, "Toggle sidebar ${shortcut(".")}", it) else HeaderButton(Lucide.Menu, "Agents and sessions", it) }
    onNew?.let { HeaderButton(Lucide.SquarePen, "New session ${shortcut("N")}", it) }
}

/** Phones: no title, just bubbles floating over the top of the messages, which fade out under them. */
@Composable
private fun FloatingBar(state: ChatState, usage: Usage?, actions: ChatActions, onToggle: (() -> Unit)?, modifier: Modifier) {
    val background = color(backgroundColor)
    Box(modifier.fillMaxWidth().height(FloatingBarHeight).background(Brush.verticalGradient(0.6f to background, 1f to background.copy(alpha = 0f)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            onToggle?.let { Bubble { BubbleButton(Lucide.Menu, "Agents and sessions", it) } }
            Spacer(Modifier.weight(1f))
            PhoneActions(state, usage, actions)
        }
    }
}

private val FloatingBarHeight = 72.dp
private val VoiceStatusHeight = 44.dp

/** Phone header buttons sit in round bubbles, a pill when there are several. */
@Composable
private fun Bubble(content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(44.dp).clip(shape).background(color(panelColor), shape).border(1.dp, color(borderColor), shape).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun BubbleButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(onBackgroundColor), modifier = Modifier.size(40.dp)) {
        Icon(icon, Modifier.size(20.dp), contentDescription = label)
    }
}

/** The phone header's trailing bubble: a new Session once this one has started, and the rest under "…". */
@Composable
private fun PhoneActions(state: ChatState, usage: Usage?, actions: ChatActions) {
    var statistics by remember(state.selected) { mutableStateOf(false) }
    val selected = state.selected?.let { id -> state.sessions.firstOrNull { it.id == id } ?: state.info }
    Bubble {
        if (state.selected != null) BubbleButton(Lucide.SquarePen, "New session", actions.onNew)
        SessionMenu(selected, actions, extra = { close ->
            DropdownMenuItem(onClick = { close(); statistics = true }) { MenuItem(Lucide.Gauge, "Statistics") }
        }) { open -> BubbleButton(Lucide.Ellipsis, "More", open) }
    }
    Popover(statistics, { statistics = false }) { ChatStatisticsContent(usage, remember(state.items) { state.items.requestMetrics() }, Modifier.fillMaxWidth()) }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(icon, Modifier.size(16.dp))
    Text(text)
}

@Composable
internal fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Tooltip(side = TooltipSide.Bottom, panel = { TooltipPanel { Text(label, fontSize = 12.sp) } }) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            style = ButtonStyle.Ghost,
            shape = Theme[shapes][mediumShape],
            contentColor = color(mutedColor),
            modifier = Modifier.size(32.dp).titleBarControl(),
        ) {
            Icon(icon, Modifier.size(17.dp), contentDescription = label)
        }
    }
}

@Composable
private fun TopBar(state: ChatState, usage: Usage?, actions: ChatActions, controls: @Composable () -> Unit) {
    TitleBarRow {
        controls()
        Spacer(Modifier.width(6.dp))
        val selected = state.sessions.firstOrNull { it.id == state.selected } ?: state.info
        Row(Modifier.weight(1f).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                selected?.title ?: state.agent?.name ?: "…",
                Modifier.weight(1f, fill = false),
                fontWeight = FontWeight.Normal,
                singleLine = true,
                overflow = TextOverflow.Ellipsis,
            )
            selected?.let { SessionBadge(it) }
        }
        ChatStatistics(usage, remember(state.items) { state.items.requestMetrics() }, state.selected)
        if (selected != null) SessionMenu(selected, actions) { open -> HeaderButton(Lucide.Ellipsis, "More", open) }
    }
}

/** Favorite and Rename for the open Session, under [trigger]; [extra] items go first. */
@Composable
private fun SessionMenu(session: SessionInfo?, actions: ChatActions, extra: @Composable DropdownMenuPanelContentScope.(close: () -> Unit) -> Unit = {}, trigger: @Composable (open: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember(session?.id) { mutableStateOf(false) }
    DropdownMenu(
        expanded = menu,
        onExpandedChange = { menu = it },
        panel = {
            DropdownMenuPanel {
                extra { menu = false }
                if (session != null) {
                    DropdownMenuItem(onClick = { menu = false; actions.onFavorite(!session.favorite) }) {
                        MenuItem(if (session.favorite) Lucide.StarOff else Lucide.Star, if (session.favorite) "Remove from favorites" else "Add to favorites")
                    }
                    DropdownMenuItem(onClick = { menu = false; renaming = true }) { MenuItem(Lucide.Pencil, "Rename") }
                }
            }
        },
    ) { trigger { menu = true } }
    if (session != null) Popover(renaming, { renaming = false }, "Rename chat") {
        val title = rememberTextFieldState(session.title)
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun save() { actions.onRename(title.text.toString()); renaming = false }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Input(title, "Chat name", Modifier.focusRequester(focus).onPreviewKeyEvent { e -> (e.type == KeyEventType.KeyDown && e.key == Key.Enter).also { if (it) save() } }, capitalize = true)
            Button(::save, Modifier.fillMaxWidth(), enabled = title.text.toString().trim() != session.title) { Text("Save") }
        }
    }
}

/**
 * A floating snackbar: nothing while connected or briefly down, e.g. back from the background, and a brief "Connected" after a
 * reconnect it showed. While the client retries: "Reconnecting" after [ReconnectingAfter], then
 * "Offline" with the reason once it's been down for [OfflineAfter].
 */
@Composable
private fun ConnectionSnackbar(connection: ConnectionState, modifier: Modifier = Modifier) {
    val connected = connection is ConnectionState.Connected
    var down by remember { mutableStateOf(false) }
    var long by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connection) { if (connection is ConnectionState.Disconnected) reason = connection.reason }
    LaunchedEffect(connected) {
        if (!connected) {
            delay(ReconnectingAfter)
            down = true
            delay(OfflineAfter - ReconnectingAfter)
            long = true
        } else if (down) {
            long = false
            delay(2_000)
            down = false
        }
    }
    // Keeps the last state while the banner slides away.
    var shown by remember { mutableStateOf(Tone.Success to "Connected") }
    if (down) shown = when {
        connected -> Tone.Success to "Connected"
        long -> Tone.Danger to (reason?.let { "Offline · $it. Retrying…" } ?: "Offline · Retrying…")
        else -> Tone.Warning to "Reconnecting…"
    }
    AnimatedVisibility(down, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val (tone, text) = shown
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.widthIn(max = 560.dp).shadow(8.dp, shape).background(color(panelColor), shape).border(1.dp, color(borderColor), shape)
                .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (tone) {
                Tone.Warning -> Spinner(size = 11.dp, tint = tone.color())
                Tone.Danger -> Icon(Lucide.WifiOff, Modifier.size(12.dp), tint = tone.color())
                else -> Icon(Lucide.Wifi, Modifier.size(12.dp), tint = tone.color())
            }
            Text(text, Modifier.weight(1f, fill = false), fontSize = 13.sp, lineHeight = 18.sp, color = tone.color())
        }
    }
}

private const val ReconnectingAfter = 3_000L
private const val OfflineAfter = 8_000L

/** Marks a Session an Agent started on its own: a Task run or a Routine's. */
@Composable
private fun SessionBadge(session: SessionInfo) = when {
    session.task != null -> Badge("Task", Lucide.SquareKanban)
    session.routine != null -> Badge("Routine", Lucide.AlarmClock)
    else -> Unit
}

/** Running: spinner; waiting on an Approval: shield; finished while elsewhere: check. */
@Composable
internal fun SessionStatusIcon(status: SessionStatus, finished: Boolean) = when {
    status == SessionStatus.Waiting -> Icon(Lucide.ShieldAlert, Modifier.size(14.dp), tint = color(warningColor), contentDescription = "Needs approval")
    status == SessionStatus.Running -> Spinner(size = 12.dp)
    finished -> Icon(Lucide.Check, Modifier.size(14.dp), tint = color(successColor), contentDescription = "Finished")
    else -> Unit
}

/** Follows the bottom while [follow] is set; scrolling up detaches it and shows a button to jump back. */
@Composable
private fun Messages(
    state: ChatState,
    listState: LazyListState,
    follow: MutableState<Boolean>,
    modifier: Modifier,
    actions: ChatActions,
    /** Room under the last message for the composer floating over it. */
    bottom: Dp = 0.dp,
    /** Room above the first message for bubbles floating over it. */
    top: Dp = 0.dp,
) {
    var following by follow
    val items = state.items
    val rows = remember(items) { items.rows() }
    val last = items.lastOrNull()
    // A trailing group of steps shows the run's progress itself.
    val thinking = state.running && last !is ChatItem.Assistant && !(last is ChatItem.ToolCall && last.waiting) && rows.lastOrNull() !is ChatRow.Steps
    val content = Triple(items.size, (last as? ChatItem.Assistant)?.text?.length ?: (last as? ChatItem.Reasoning)?.text?.length, thinking)
    var seen by remember { mutableStateOf(content) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.canScrollForward) }
            .collect { (index, offset, canScrollForward) ->
                val up = index < previous.first || (index == previous.first && offset < previous.second)
                if (up && listState.isScrollInProgress && canScrollForward) following = false
                else if (!canScrollForward && listState.layoutInfo.totalItemsCount > 0) following = true
                previous = index to offset
            }
    }
    LaunchedEffect(content, following) {
        if (following) {
            seen = content
            if (rows.isNotEmpty() || thinking) {
                listState.scrollToItem((rows.size - if (thinking) 0 else 1).coerceAtLeast(0))
                listState.scrollBy(100_000f)
            }
        }
    }
    LaunchedEffect(listState, state.hasOlder) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.layoutInfo.totalItemsCount > 0 }
            .collect { atTop -> if (atTop && state.hasOlder) actions.onLoadOlder() }
    }
    if (items.isEmpty()) {
        // Until the first catch-up there's nothing to say yet; the empty state is only for Sessions that are empty.
        if (state.loaded) EmptyState(state, modifier.padding(top = top, bottom = bottom)) else Box(modifier.fillMaxWidth())
        return
    }
    Box(modifier.fillMaxWidth()) {
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, top = 20.dp + top, end = 16.dp, bottom = 20.dp + bottom),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(rows, key = { it.key }, contentType = {
                    when (it) {
                        is ChatRow.Single -> it.item::class
                        is ChatRow.Steps -> ChatRow.Steps::class
                    }
                }) { row ->
                    // A sent message replaces its outgoing copy in place, so only the outgoing one animates in.
                    val user = (row as? ChatRow.Single)?.item is ChatItem.User
                    Box(Modifier.animateItem(fadeInSpec = if (user) null else tween(250), fadeOutSpec = null).widthIn(max = ContentWidth).fillMaxWidth()) {
                        when (row) {
                            is ChatRow.Single -> if (row.key == "outgoing") Entering { ChatItemView(row.item, actions) }
                                else ChatItemView(row.item, actions, state.voice?.spoken?.get(row.item.id), state.answers)
                            is ChatRow.Steps -> StepsView(row, active = state.running && row === rows.last())
                        }
                    }
                }
                state.voice?.heard?.takeIf { it.isNotBlank() }?.let { heard ->
                    item(key = "heard") { Box(Modifier.animateItem(fadeOutSpec = null).widthIn(max = ContentWidth).fillMaxWidth().alpha(0.6f)) { Entering { ChatItemView(ChatItem.User("heard", heard), actions) } } }
                }
                if (thinking) item(key = "thinking") {
                    Row(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spinner(tint = color(accentColor))
                        Text("Working…", color = color(mutedColor), fontSize = 13.sp)
                    }
                }
            }
        }
        // Touch screens scroll by swiping; estimating mixed-height rows there only made the bar jump.
        if (LocalInteractionMode.current != InteractionMode.Touch)
            VerticalScrollbar(rememberVerticalScrollbarState(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom))
        AnimatedVisibility(
            !following && listState.canScrollForward,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp + bottom),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            JumpToBottom(unseen = content != seen) {
                scope.launch {
                    listState.animateScrollToItem((rows.size - if (thinking) 0 else 1).coerceAtLeast(0))
                    following = true
                }
            }
        }
    }
}

@Composable
private fun DropOverlay() {
    val shape = Theme[shapes][largeShape]
    Box(Modifier.fillMaxSize().padding(12.dp).background(color(backgroundColor).copy(alpha = 0.85f), shape).border(2.dp, color(accentColor).copy(alpha = 0.6f), shape), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Lucide.Paperclip, Modifier.size(28.dp), tint = color(accentColor))
            Text("Drop to attach", fontSize = 15.sp)
            Text("4 files · 10 MB each", fontSize = 12.sp, color = color(mutedColor))
        }
    }
}

@Composable
private fun Entering(content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it }
        + scaleIn(initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f))) { content() }
}

@Composable
private fun JumpToBottom(unseen: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(40.dp).widthIn(min = 40.dp).shadow(6.dp, shape).clip(shape).background(color(panelColor)).border(1.dp, color(borderColor), shape)
            .clickable(onClick = onClick).padding(horizontal = if (unseen) 14.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.ArrowDown, Modifier.size(16.dp), contentDescription = "Jump to the latest")
        if (unseen) Text("New messages", fontSize = 12.sp)
    }
}

@Composable
private fun ChatItemView(item: ChatItem, actions: ChatActions, spoken: Int? = null, answers: Map<String, Map<String, List<String>>> = emptyMap()) {
    when (item) {
        is ChatItem.User -> if (item.routine != null) Expandable(Lucide.AlarmClock, requireNotNull(item.routine), subtitle = "routine") { RichMarkdown(item.text) }
        else if (item.task != null) Expandable(Lucide.SquareKanban, requireNotNull(item.task), subtitle = "task") { RichMarkdown(item.text) }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Panel(
                Modifier.widthIn(max = 560.dp),
                color = color(selectedControlColor),
                borderColor = androidx.compose.ui.graphics.Color.Transparent,
                shape = Theme[shapes][largeShape],
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.text.isNotBlank()) RichMarkdown(item.text)
                item.attachments.forEach { a ->
                    LaunchedEffect(a.id) { if (a.mimeType.startsWith("image/")) actions.onLoadAttachment(a.id) }
                    AttachmentChip(a, actions.downloads[a.id]?.base64, onPreview = { actions.onPreviewAttachment(a.id) }, onOpen = { actions.onSaveAttachment(a.id) })
                }
            } }
        }
        is ChatItem.Assistant -> if (spoken != null) SpokenText(item.text, spoken, Modifier.fillMaxWidth()) else Markdown(item.text, Modifier.fillMaxWidth())
        is ChatItem.Reasoning -> StepView(item)
        is ChatItem.ToolCall -> when {
            item.awaitingApproval -> ApprovalCard(item, actions.onDecision)
            item.questions != null && item.name == "request_takeover" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                QuestionsCard(requireNotNull(item.questions), answers, actions)
                Button(onClick = actions.onWatch, style = ButtonStyle.Outlined) { Text("Open Live view") }
            }
            item.questions != null -> QuestionsCard(requireNotNull(item.questions), answers, actions)
            (item.identity?.instance?.pluginId == "kinuvo.computer" && item.identity?.localId in setOf("computer_capture", "computer_act")) && item.result == null -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ToolCallView(item)
                Button(onClick = actions.onWatch, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Text("Watch live", fontSize = 12.sp) }
            }
            item.name == "ask_user" && item.result != null && !item.isError -> AnsweredQuestions(item)
            item.name == "artifact" && item.result != null && !item.isError -> ArtifactCard(item, actions.onOpenArtifact)
            else -> ToolCallView(item)
        }
        is ChatItem.Error -> Panel(
            Modifier.fillMaxWidth(),
            color = color(destructiveColor).copy(alpha = 0.08f),
            borderColor = color(destructiveColor).copy(alpha = 0.3f),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Lucide.CircleX, Modifier.size(16.dp).padding(top = 2.dp), tint = color(destructiveColor))
                Text(item.message, color = color(destructiveColor), fontSize = 13.sp)
            }
        }
        is ChatItem.Measurement -> Unit
        is ChatItem.Reflection -> ReflectionView(item, onReview = actions.onReviewReflection)
        is ChatItem.Compaction -> CompactionMarker(item)
        is ChatItem.Activation -> Expandable(Lucide.BookOpen, item.name, subtitle = "skill") { Markdown(item.content) }
    }
}

@Composable
private fun EmptyState(state: ChatState, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val avatar = when {
                state.running -> AvatarState.Working
                state.voice?.active == true || state.dictation?.dictating == true -> AvatarState.Listening
                else -> AvatarState.Idle
            }
            RiveAvatar(BundledAvatar, Modifier.size(180.dp, 120.dp), LocalRiveGaze.current, state.agent?.avatarArtboard, state = avatar)
            Text(state.agent?.let { "Start a session with ${it.name}" } ?: "Connecting…", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("Ask anything, or type /skill to activate one.", color = color(mutedColor))
        }
    }
}

/** Collapsible row used for tool calls, skills and reasoning. */
@Composable
private fun Expandable(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    status: (@Composable () -> Unit)? = null,
    border: Boolean = true,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val shape = Theme[shapes][mediumShape]
    Disclosure(expanded, { expanded = it }, Modifier.fillMaxWidth().then(if (border) Modifier.border(1.dp, color(borderColor), shape) else Modifier)) {
        Column {
            ListRow(selected = false, onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 36.dp)) {
                Icon(icon, Modifier.size(15.dp), tint = color(mutedColor))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Normal, singleLine = true)
                    subtitle?.let { Text(it, fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis) }
                    status?.invoke()
                }
                Icon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
            }
            DisclosurePanel(contentPadding = PaddingValues(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 10.dp)) { content() }
        }
    }
}

private class ToolLabel(val icon: ImageVector, val title: String, val subtitle: String?, val skill: Boolean = false)

private fun ChatItem.ToolCall.label(): ToolLabel {
    val identity = identity
    val local = identity?.localId ?: name
    val skill = if (identity?.instance?.pluginId == "kinuvo.core" && local == "activate_skill") Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(args)?.groupValues?.get(1) else null
    return when {
        skill != null -> ToolLabel(Lucide.BookOpen, skill, "skill", skill = true)
        identity?.instance?.pluginId == "kinuvo.exec" && local == "shell" -> ToolLabel(Lucide.Terminal, local, commandOf(args)?.lineSequence()?.firstOrNull()?.take(80))
        identity != null -> ToolLabel(Lucide.Plug, local, identity.instance.let { "${it.pluginId} · ${it.instanceId}" })
        else -> ToolLabel(Lucide.Wrench, name, null)
    }
}

@Composable
private fun StepView(item: ChatItem, border: Boolean = true) {
    when (item) {
        is ChatItem.ToolCall -> ToolCallView(item, border)
        is ChatItem.Reasoning -> Expandable(Lucide.Brain, "Thinking", border = border) {
            Text(item.text, style = LocalTextStyle.current.copy(fontStyle = FontStyle.Italic), color = color(mutedColor), fontSize = 13.sp)
        }
        else -> Unit
    }
}

/**
 * Reasoning and tool calls on one line: the current step while [active], else a summary with any failures. Expands to
 * the steps, each expanding to its arguments and output. A single finished step shows as itself.
 */
@Composable
internal fun StepsView(row: ChatRow.Steps, active: Boolean, initiallyExpanded: Boolean = false) {
    row.steps.singleOrNull()?.takeIf { !active }?.let { return StepView(it) }
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val failed = row.failed
    val shape = Theme[shapes][mediumShape]
    val edge = if (failed > 0) color(destructiveColor).copy(alpha = 0.45f) else color(borderColor)
    Disclosure(expanded, { expanded = it }, Modifier.fillMaxWidth().border(1.dp, edge, shape)) {
        Column {
            ListRow(
                selected = false,
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 36.dp).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            ) {
                val current = row.steps.last()
                val running = (current as? ChatItem.ToolCall)?.takeIf { it.result == null }?.label()
                when {
                    active -> Spinner(size = 15.dp, tint = color(accentColor))
                    failed > 0 -> Icon(Lucide.CircleX, Modifier.size(15.dp), tint = color(destructiveColor))
                    else -> Icon(Lucide.ListChecks, Modifier.size(15.dp), tint = color(mutedColor))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val title = when {
                        active && running != null -> "Running ${running.title}"
                        active && current is ChatItem.Reasoning -> "Thinking…"
                        else -> row.summary()
                    }
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                    if (active) running?.subtitle?.let { Text(it, fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis) }
                    else Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        row.tools.take(MaxStepIcons).forEach { Icon(it.label().icon, Modifier.size(12.dp), tint = color(if (it.isError) destructiveColor else mutedColor)) }
                        if (row.tools.size > MaxStepIcons) Text("+${row.tools.size - MaxStepIcons}", fontSize = 11.sp, color = color(mutedColor))
                    }
                }
                if (failed > 0) Text("$failed failed", fontSize = 12.sp, color = color(destructiveColor), singleLine = true)
                else if (active && row.steps.size > 1) Text("${row.steps.size} steps", fontSize = 12.sp, color = color(mutedColor), singleLine = true)
                Icon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
            }
            DisclosurePanel(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
                Column {
                    HorizontalSeparator(Modifier.padding(bottom = 4.dp))
                    row.steps.forEach { key(it.id) { StepView(it, border = false) } }
                }
            }
        }
    }
}

private const val MaxStepIcons = 6

/** E.g. "Thought, ran 3 tools" or "Ran shell". */
private fun ChatRow.Steps.summary(): String {
    val tools = tools
    val ran = when (tools.size) {
        0 -> null
        1 -> "ran ${tools[0].label().title}"
        else -> "ran ${tools.size} tools"
    }
    return listOfNotNull("thought".takeIf { steps.any { it is ChatItem.Reasoning } }, ran).joinToString(", ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun ToolCallView(item: ChatItem.ToolCall, border: Boolean = true) {
    val label = item.label()
    Expandable(label.icon, label.title, label.subtitle, border = border, status = {
        when {
            item.result == null -> Spinner(size = 13.dp)
            item.isError -> Icon(Lucide.CircleX, Modifier.size(14.dp), tint = color(destructiveColor))
            else -> Icon(Lucide.CircleCheck, Modifier.size(14.dp), tint = color(successColor))
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!label.skill && item.args.isNotBlank()) CodeBlock(prettyJson(item.args), language = "arguments")
            item.result?.let { CodeBlock(it, language = if (item.isError) "error" else "result", previewChars = 4000) }
        }
    }
}

/** A past `ask_user` form: each question with what the user answered. */
@Composable
private fun AnsweredQuestions(item: ChatItem.ToolCall) {
    val args = runCatching { Json.parseToJsonElement(item.args).jsonObject }.getOrNull()
    val questions = runCatching { ProtocolJson.decodeFromJsonElement(ListSerializer(Question.serializer()), args!!.getValue("questions")) }.getOrDefault(emptyList())
    val answers = runCatching { Json.parseToJsonElement(item.result.orEmpty()).jsonObject }.getOrNull()
    Expandable(Lucide.MessageCircleQuestionMark, args?.get("title")?.jsonPrimitive?.content?.ifBlank { null } ?: "Questions", if (answers == null) "skipped" else "answered") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            questions.forEach { q ->
                val answer = answers?.get(q.id)?.let { a -> (a as? JsonArray)?.joinToString { it.jsonPrimitive.content } ?: a.jsonPrimitive.content }
                Column {
                    Text(q.label, fontSize = 12.sp, color = color(mutedColor))
                    Text(answer ?: "—", fontSize = 13.sp)
                }
            }
        }
    }
}

/** A saved Artifact; its details come from the tool's JSON result. */
@Composable
private fun ArtifactCard(item: ChatItem.ToolCall, onOpen: (id: String) -> Unit) {
    val saved = runCatching { Json.parseToJsonElement(item.result.orEmpty()).jsonObject }.getOrNull()
    val id = saved?.get("id")?.jsonPrimitive?.content ?: return ToolCallView(item)
    Panel(Modifier.fillMaxWidth(), shape = Theme[shapes][largeShape], contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Lucide.Shapes, Modifier.size(20.dp), tint = color(accentColor))
            Column(Modifier.weight(1f)) {
                Text(saved["title"]?.jsonPrimitive?.content ?: id, fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(saved["type"]?.jsonPrimitive?.content, "artifact").joinToString(" · "), fontSize = 12.sp, color = color(mutedColor), singleLine = true)
            }
            Button(onClick = { onOpen(id) }, style = ButtonStyle.Outlined) { Text("Open") }
        }
    }
}

@Composable
private fun ApprovalCard(item: ChatItem.ToolCall, onDecision: (String, ApprovalDecision) -> Unit) {
    val note = rememberTextFieldState()
    var showArgs by remember { mutableStateOf(false) }
    // A Script's call waits on its own Approval, shown on the Script's card.
    val name = item.approval?.toolCallName ?: item.name
    val args = item.approval?.args ?: item.args
    val identity = item.approval?.identity ?: item.identity
    val label = identity?.let { "${it.instance.pluginId} › ${it.instance.instanceId} › ${it.localId}" } ?: name
    Panel(
        Modifier.fillMaxWidth(),
        color = color(panelColor),
        borderColor = color(accentColor).copy(alpha = 0.5f),
        shape = Theme[shapes][largeShape],
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Lucide.ShieldAlert, Modifier.size(18.dp), tint = color(accentColor))
                Text("Allow $label?", fontWeight = FontWeight.Normal)
                item.approval?.let { a ->
                    Pill(a.risk.name + " risk", tone = when (a.risk) { Risk.Low -> Tone.Neutral; Risk.Medium -> Tone.Warning; Risk.High -> Tone.Danger })
                }
            }
            item.approval?.reason?.let { Text(it, color = color(mutedColor), fontSize = 12.sp) }
            val command = if (identity?.instance?.pluginId == "kinuvo.exec" && identity.localId == "shell") commandOf(args) else null
            command?.let { CodeBlock(it, language = "shell") }
            Button(onClick = { showArgs = !showArgs }, style = ButtonStyle.Link, contentColor = color(mutedColor)) {
                Text(if (showArgs) "Hide arguments" else "Show arguments", fontSize = 12.sp)
            }
            if (showArgs) CodeBlock(prettyJson(args), language = "arguments")
            Input(note, "Note for the agent (optional, sent on Deny)", capitalize = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDecision(item.id, ApprovalDecision.AllowOnce) }, style = ButtonStyle.Primary) { Text("Allow once") }
                if (item.approval?.trustable != false) Button(onClick = { onDecision(item.id, ApprovalDecision.AlwaysAllow) }, style = ButtonStyle.Outlined) {
                    Text(item.trustHint?.let { if (identity?.instance?.pluginId == "kinuvo.computer" && identity.localId == "computer_act") "Always allow in $it" else "Always allow `$it …`" } ?: "Always allow", singleLine = true)
                }
                Button(onClick = { onDecision(item.id, ApprovalDecision.Deny(note.text.toString())) }, style = ButtonStyle.Ghost, contentColor = color(destructiveColor)) {
                    Text("Deny")
                }
            }
        }
    }
}

@Composable
internal fun RequestMetricsView(metrics: kinuvo.protocol.RequestMetrics, initiallyExpanded: Boolean = false) {
    val cached = metrics.cachedTokens?.let { "$it cached" } ?: "cache not reported"
    Expandable(Lucide.Gauge, "${metrics.kind} request", "$cached · ${metrics.latencyMs} ms", initiallyExpanded = initiallyExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${metrics.provider} · ${metrics.model} · ${metrics.reasoning}", fontSize = 12.sp)
            Text("Duration: ${metrics.latencyMs} ms", fontSize = 12.sp)
            Text("Input: ${metrics.inputTokens ?: "not reported"} · Output: ${metrics.outputTokens ?: "not reported"}", fontSize = 12.sp)
            Text("Cache reads: ${metrics.cachedTokens ?: "not reported"} · Cache writes: ${metrics.cacheWriteTokens ?: "not reported"}", fontSize = 12.sp)
            metrics.firstTokenMs?.let { Text("First token: $it ms", fontSize = 12.sp) }
            if (!metrics.success) Text("Request did not finish", fontSize = 12.sp, color = color(destructiveColor))
        }
    }
}

@Composable
internal fun ReflectionView(item: ChatItem.Reflection, initiallyExpanded: Boolean = false, onReview: (String, Boolean) -> Unit = { _, _ -> }) {
    val result = item.result
    val proposals = result?.proposals.orEmpty()
    val pending = proposals.count { it.id !in item.reviews }
    val knowledge = (result?.knowledge ?: 0) + proposals.count { it.tool == "knowledge" && item.reviews[it.id] == true }
    val skills = proposals.count { it.tool == "skill_manage" && item.reviews[it.id] == true }
    val saved = (result?.total ?: 0) + proposals.count { item.reviews[it.id] == true }
    val title = when {
        result == null -> "Reflecting…"
        pending > 0 -> "Reflection needs your review"
        result.error != null -> "Reflection failed"
        result.total == 0 && result.proposals.isEmpty() -> "Nothing to save"
        else -> "Reflection complete"
    }
    val subtitle = when {
        pending > 0 -> "$pending pending"
        saved > 0 -> "$saved saved"
        else -> null
    }
    Expandable(Lucide.Sparkles, title, subtitle, initiallyExpanded = initiallyExpanded, status = {
        when {
            result == null -> Spinner(size = 13.dp)
            pending > 0 -> Icon(Lucide.Shield, Modifier.size(14.dp), tint = color(mutedColor))
            result.error != null -> Icon(Lucide.CircleX, Modifier.size(14.dp), tint = color(destructiveColor))
            else -> Icon(Lucide.CircleCheck, Modifier.size(14.dp), tint = color(successColor))
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result == null) Text("Reviewing this conversation for Knowledge, Memory, User Notes and Skills.", fontSize = 13.sp)
            else {
                Text("Saved: Knowledge $knowledge · Memory ${result.memory} · User Notes ${result.userNotes} · Skills $skills", fontSize = 13.sp)
                result.memoryChanges.forEach { change ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (change.target == "memory") "Memory update" else "User Notes update", fontSize = 13.sp)
                        ReflectionDiff(remember(change) { lineDiff(change.before, change.after) })
                    }
                }
                result.proposals.forEach { proposal ->
                    ReflectionProposalView(proposal, item.reviews[proposal.id], onReview)
                }
                if (result.measurements.isNotEmpty()) {
                    Text(if (result.warmContext) "Replayed the parent request context" else "Parent request snapshot unavailable", fontSize = 12.sp, color = color(mutedColor))
                }
                result.error?.let { Text(it, color = color(destructiveColor), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun ReflectionProposalView(proposal: kinuvo.protocol.ReflectionProposal, approved: Boolean?, onReview: (String, Boolean) -> Unit) {
    val skill = proposal.tool == "skill_manage"
    val title = proposal.args[if (skill) "name" else "title"]?.jsonPrimitive?.content ?: proposal.note?.title.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val kind = when {
            !skill -> "Knowledge proposal"
            proposal.skillBefore != null -> "Skill update"
            else -> "Skill draft"
        }
        Text("$kind: $title", fontWeight = FontWeight.Normal, fontSize = 13.sp)
        Text(proposal.reason, fontSize = 13.sp)
        // Evidence quotes the conversation; one line is enough to place it.
        Text("Evidence: ${proposal.evidence}", fontSize = 12.sp, color = color(mutedColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val content = proposal.args["content"]?.jsonPrimitive?.content.orEmpty()
        val edited = proposal.skillBefore?.takeIf { skill }
        // An edited Skill shows only what changes; its whole file stays out of the way.
        if (edited != null) {
            ReflectionDiff(remember(edited, content) { lineDiff(edited, content) })
            ReviewButtons(proposal.id, approved, onReview)
            return@Column
        }
        val before = proposal.note?.body
        if (before != null) Expandable(Lucide.FileText, "Current content") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                proposal.note?.let { note ->
                    Text("${note.title} · ${note.type}", fontSize = 13.sp)
                    note.properties.forEach { (key, value) -> Text("$key: $value", fontSize = 13.sp) }
                }
                Markdown(before)
            }
        }
        Expandable(Lucide.FileText, if (skill) "New skill" else "Proposed change", if (skill) "${content.lines().size} lines" else null, initiallyExpanded = !skill) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (skill) Markdown(content)
                else proposal.args.forEach { (key, value) ->
                    val text = value.jsonPrimitive.content
                    if (text.isNotBlank() && key != "ref") {
                        if (key == "append") Markdown(text)
                        else Text("${when (key) {
                            "action" -> "Action"
                            "type" -> "Note type"
                            "title" -> "Title"
                            "set" -> "Properties"
                            "under" -> "Under heading"
                            "link_text" -> "Link text"
                            "link_to" -> "Link target"
                            else -> key
                        }}: $text", fontSize = 13.sp)
                    }
                }
            }
        }
        ReviewButtons(proposal.id, approved, onReview)
    }
}

@Composable
private fun ReviewButtons(id: String, approved: Boolean?, onReview: (String, Boolean) -> Unit) {
    if (approved == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onReview(id, true) }, style = ButtonStyle.Primary) { Text("Approve") }
        Button(onClick = { onReview(id, false) }, style = ButtonStyle.Ghost) { Text("Reject") }
    } else Text(if (approved) "Approved and saved" else "Rejected", fontSize = 12.sp, color = color(mutedColor))
}

/** Added and removed lines, summed up as "+3 −1". */
@Composable
private fun ReflectionDiff(diff: List<Pair<Boolean, String>>) {
    val added = diff.count { it.first }
    val summary = listOfNotNull("+$added".takeIf { added > 0 }, "−${diff.size - added}".takeIf { diff.size > added }).joinToString(" ").ifEmpty { "no changes" }
    Expandable(Lucide.FileDiff, "Changes", summary, initiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().background(color(codeColor), Theme[shapes][smallShape]).padding(8.dp)) {
            diff.forEach { (add, line) ->
                val tone = if (add) successColor else destructiveColor
                Text((if (add) "+ " else "− ") + line, Modifier.fillMaxWidth().background(color(tone).copy(alpha = 0.1f)).padding(horizontal = 4.dp),
                    style = MonoStyle, color = color(tone))
            }
        }
    }
}

/** The lines removed from [before] (false) and added in [after] (true), in order, by longest common subsequence. */
internal fun lineDiff(before: String, after: String): List<Pair<Boolean, String>> {
    val a = before.trimEnd().takeIf { it.isNotEmpty() }?.lines().orEmpty()
    val b = after.trimEnd().takeIf { it.isNotEmpty() }?.lines().orEmpty()
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed())
        lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    val out = mutableListOf<Pair<Boolean, String>>()
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) when {
        i < a.size && j < b.size && a[i] == b[j] -> { i++; j++ }
        i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> out += false to a[i++]
        else -> out += true to b[j++]
    }
    return out
}

@Composable
private fun CompactionMarker(item: ChatItem.Compaction) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalSeparator(Modifier.weight(1f))
            Button(onClick = { expanded = !expanded }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(mutedColor)) {
                Icon(Lucide.Archive, Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "Earlier conversation compacted" else "Earlier conversation compacted · show summary", fontSize = 12.sp)
            }
            HorizontalSeparator(Modifier.weight(1f))
        }
        if (expanded) Markdown(item.summary, Modifier.padding(top = 8.dp))
    }
}

private fun commandOf(args: String): String? =
    runCatching { Json.parseToJsonElement(args).jsonObject["command"]?.jsonPrimitive?.content }.getOrNull()

private val PrettyJson = Json { prettyPrint = true }

private fun prettyJson(args: String): String =
    runCatching { PrettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement(args)) }.getOrDefault(args)
