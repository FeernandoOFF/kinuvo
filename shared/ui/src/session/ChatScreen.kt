package kinuvo.ui.session

import kinuvo.app.surface.SurfaceImages
import kinuvo.protocol.McpAppResource
import kotlinx.serialization.json.JsonObject
import kinuvo.protocol.SurfaceAction

import kinuvo.protocol.ScreenRef
import kinuvo.app.connection.*
import kinuvo.app.session.*
import org.koin.compose.koinInject
import kinuvo.app.agent.*
import kinuvo.app.settings.*
import kinuvo.ui.settings.*
import kinuvo.app.navigation.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kinuvo.client.ConnectionState
import kinuvo.protocol.ExecutionPrompt
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.AgentSettings
import kinuvo.protocol.SessionSettings
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.Usage
import kinuvo.protocol.ServerFeature
import kinuvo.protocol.supports
import kinuvo.ui.design.*
import kinuvo.protocol.UploadAttachmentParams
import kotlinx.coroutines.launch
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

internal val ContentWidth = 760.dp

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
    onBack: (() -> Unit)? = null,
    dock: BrowserDock? = null,
) {
    val appState by app.state.collectAsState()
    val current by session.state.collectAsState()
    val route = current.route
    val connection by session.connection.collectAsState()
    val pickAttachment = rememberAttachmentPicker(session::addAttachments)
    val saveAttachment = rememberAttachmentSaver(session::attachmentError)
    val speech = getKoin().getOrNull<SpeechEngine>()
    val voice = speech?.let { koinViewModel<VoiceViewModel> { parametersOf(it, session) }.chat }
    val liveCall by koinInject<LiveCalls>().state.collectAsState()
    val dictation = speech?.let { koinViewModel<DictationViewModel> { parametersOf(it) } }
    // The ViewModels outlive the screen, e.g. while Settings is open; the microphone doesn't.
    // A live call belongs to the app, not this screen, so it carries on after leaving.
    DisposableEffect(voice, dictation) { onDispose { if (voice?.state?.value?.live != true) voice?.end(); dictation?.stop() } }
    // Picking a photo takes the app out of view too; voice chat waits for it instead of ending.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { voice?.background(); dictation?.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { voice?.foreground() }
    val initialDraft = remember { app.draft(route) }
    val draft = rememberTextFieldState(initialDraft, TextRange(initialDraft.length))
    // Saved under the Session once it has an ID, so it comes back wherever the Session is reopened from.
    val drafted by rememberUpdatedState(route)
    DisposableEffect(draft, route.slot) { onDispose { app.saveDraft(drafted, draft.text.toString()) } }
    LaunchedEffect(route.agentId, route.sessionId, current.resolving) { session.shown() }
    val harnesses by org.koin.compose.koinInject<HarnessRepository>().state.collectAsState()
    val agent = appState.agents.firstOrNull { it.id == route.agentId }
    val state = ChatState(
        agent = agent,
        harness = agent?.let { harnessName(it, harnesses.connections) },
        sessions = route.agentId?.let { appState.sessions.lists[it] }?.sessions.orEmpty(),
        selected = route.sessionId,
        info = current.info?.takeIf { it.id == route.sessionId },
        items = current.items,
        loaded = current.loaded,
        hasOlder = current.data?.hasOlder == true,
        running = current.running,
        completions = if (agent?.external != true && current.capabilities?.server.supports(ServerFeature.Skills)) listOf(CompletionSource('/', current.skills.filter { it.active && it.userInvocable }.map { Completion(it.name, it.description) }, startOnly = true)) else emptyList(),
        settings = current.settings,
        defaults = current.defaults,
        models = current.models,
        attachments = current.attachments,
        unsent = current.unsent,
        sending = current.sending,
        capabilities = current.capabilities,
        // While a live call runs, it owns the voice UI; this screen's regular voice chat stays out of the way.
        voice = voice?.state?.collectAsState()?.value?.takeIf { liveCall == null || it.live },
        dictation = dictation?.state?.collectAsState()?.value,
        preview = current.preview?.let { id ->
            current.attachments.firstOrNull { it.id == id }?.let { AttachmentPreview(it.attachment, it.upload.base64) }
                ?: current.downloads[id]?.let { AttachmentPreview(it.attachment.copy(id = id), it.base64) }
                ?: current.items.attachment(id)?.let { AttachmentPreview(it, null) }
        },
        answers = current.answers,
        approval = current.data?.live?.approval,
        questions = current.data?.live?.questions,
        prompts = current.prompts,
        phase = current.phase,
        resume = current.capabilities?.execution?.resume,
        resuming = current.resuming,
    )
    CompositionLocalProvider(LocalSurfaceImages provides getKoin().getOrNull<SurfaceImages>()) {
        ChatContent(
            state, connection, appState.usage,
            syncedAt = appState.syncedAt,
            actions = ChatActions(
                onNew = { if (route.sessionId != null) onOpen(Route.Chat(route.agentId, compose = true)) },
                onSelect = { id -> if (id != route.sessionId) onOpen(Route.Chat(route.agentId, id)) },
                onSend = session::send,
                onSurfaceAction = { text, action -> session.send(text, action = action) },
                onAppResource = session::appResource,
                onAppTool = session::appCallTool,
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
                onScreen = { screen, reason ->
                    val owner = screen.owner
                    if (owner != null && dock != null) dock.show(screen, reason)
                    else onNavigate(if (owner == null) Route.Computers(screen.id, reason) else Route.Browser(owner.agentId, route.sessionId, screen, reason))
                },
                onBrowser = if (sessionBrowserAvailable(agent, current.capabilities?.server) && route.agentId != null)
                    ({ dock?.toggle?.invoke() ?: onNavigate(Route.Browser(requireNotNull(route.agentId), route.sessionId)) }) else null,
                browserOpen = dock?.open == true,
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
                onExternal = session::answerExternal,
                onCheckHarness = { appState.agents.firstOrNull { it.id == route.agentId }?.connectionId?.let(session::checkHarness) },
                onCloseSession = session::closeExternal,
                children = current.children,
                agents = appState.agents,
                agentId = route.agentId,
                onOpenSession = { agentId, id -> onOpen(Route.Chat(agentId, id)) },
            ),
            wide = wide,
            compose = route.compose && route.sessionId == null,
            draft = draft,
            onToggleShell = onToggleShell ?: { if (wide) shell.open = !shell.open else shell.revealed = true },
            onBack = onBack,
        )
    }
}

class ChatActions(
    val onNew: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onSurfaceAction: (String, SurfaceAction) -> Unit = { _, _ -> },
    /** The HTML of the MCP App a tool call shows, and its calls to its own MCP server's tools. */
    val onAppResource: suspend (callId: String) -> McpAppResource = { error("MCP Apps are unavailable") },
    val onAppTool: suspend (callId: String, name: String, arguments: JsonObject) -> JsonObject = { _, _, _ -> error("MCP Apps are unavailable") },
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
    val onReviewReflection: (String, Boolean, String?) -> Unit = { _, _, _ -> },
    /** The open `ask_user` form's answers by question ID; null skips it. */
    val onAnswer: (Map<String, List<String>>?) -> Unit = {},
    val onEditAnswer: (questionsId: String, questionId: String, value: List<String>) -> Unit = { _, _, _ -> },
    val onOpenArtifact: (id: String) -> Unit = {},
    /** Opens Live view of the Computers an Agent controls. */
    val onWatch: () -> Unit = {},
    val onScreen: (ScreenRef, String?) -> Unit = { _, _ -> },
    val onBrowser: (() -> Unit)? = null,
    val browserOpen: Boolean = false,
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
    /** Answers an external Agent's harness with its own JSON response. */
    val onExternal: (ExecutionPrompt, kotlinx.serialization.json.JsonElement) -> Unit = { _, _ -> },
    val onCheckHarness: () -> Unit = {},
    /** Reopens an external Session whose harness process ended. */
    /** Ends an external Session's harness process; its transcript stays. */
    val onCloseSession: () -> Unit = {},
    /** The Sessions the open one delegated to, and the Agents they and its parent belong to. */
    val children: List<SessionInfo> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    /** The open Session's Agent, which a delegation without one goes to. */
    val agentId: String? = null,
    val onOpenSession: (agentId: String, sessionId: String) -> Unit = { _, _ -> },
)

/** The Browser panel beside a wide chat: [toggle] shows or hides it; [show] opens it on the Screen a card points at. */
class BrowserDock(val open: Boolean, val toggle: () -> Unit, val show: (ScreenRef, String?) -> Unit)

/** The Servers saved on this Device, for the shell's menu; [active] is the connected one. */
data class ServerMenuState(
    val servers: List<Server> = emptyList(),
    val active: Server? = null,
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
    /** When what's shown was last current, for the offline notice. */
    syncedAt: Long? = null,
    wide: Boolean? = null,
    compose: Boolean = false,
    onToggleShell: (() -> Unit)? = null,
    draft: TextFieldState = rememberTextFieldState(),
    onBack: (() -> Unit)? = null,
) {
    // Hoisted above the layout so the draft and scroll survive rotation and process death.
    val messages = rememberLazyListState()
    val follow = rememberSaveable { mutableStateOf(true) }
    val root = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var composerFocused by remember { mutableStateOf(false) }
    val composerFocus = remember { FocusRequester() }
    var completing by remember { mutableStateOf(false) }
    val requestInputs = remember { RequestInputs() }
    LaunchedEffect(state.requests.firstOrNull()?.id) { completing = false }
    val scope = rememberCoroutineScope()
    val connectionNotice = connectionNotice(connection, syncedAt)
    LaunchedEffect(Unit) { if (compose) composerFocus.requestFocus() }

    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val wide = wide ?: (maxWidth >= 720.dp)
        val compactHeader = maxWidth < 520.dp
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
                e.isShortcutPressed && e.isShiftPressed && e.key == Key.Period && state.running && state.canStop -> actions.onStop()
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
            if (!floating) TopBar(state, usage, actions, compact = compactHeader) {
                HeaderControls(wide, toggle, actions.onNew.takeIf { !wide && state.selected != null })
            }
            // The composer floats over the messages, which scroll on under its blur.
            var dropping by remember { mutableStateOf(false) }
            val canDrop = !state.running && (state.files?.actionable != false || state.photos?.actionable != false)
            // Like the keyboard covering it: typing into a Surface or web page leaves the composer behind the keyboard.
            val composerHidden = floating && !composerFocused && WindowInsets.ime.getBottom(density) > 0
            // Hides at once, under the rising keyboard; slides back up when it closes.
            val composerShift = remember { Animatable(0f) }
            LaunchedEffect(composerHidden) { if (composerHidden) composerShift.snapTo(1f) else composerShift.animateTo(0f, Motion.appearSpring()) }
            // Its blur would draw HTML Surface web views into an empty layer while it moves, crashing Android's WebView.
            val composerBlurred by remember { derivedStateOf { composerShift.value == 0f } }
            Box(Modifier.weight(1f).attachmentDrop(canDrop, actions.onDropAttachments) { dropping = it }) {
                val voice = state.voice?.takeIf { it.active && !it.live }
                // Keeps the last state while voice chat animates away.
                var shownVoice by remember { mutableStateOf(voice) }
                voice?.let { shownVoice = it }
                CompositionLocalProvider(LocalRiveGaze provides gaze) {
                    Messages(state, messages, follow, Modifier.fillMaxSize().hazeSource(haze), actions, if (composerHidden) 0.dp else composerHeight,
                        top = (if (floating) FloatingBarHeight else 0.dp) + (if (voice != null) VoiceStatusHeight else 0.dp))
                }
                state.voice?.takeIf { !it.live }?.let { VoiceGlow(it, Modifier.align(Alignment.BottomCenter)) }
                androidx.compose.animation.AnimatedVisibility(dropping, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) { DropOverlay() }
                if (floating) FloatingBar(state, usage, actions, toggle, onBack, Modifier.align(Alignment.TopCenter))
                androidx.compose.animation.AnimatedVisibility(voice != null, Modifier.align(Alignment.TopCenter).padding(top = if (floating) FloatingBarHeight + Spacing.space8 else Spacing.space12),
                    enter = fadeIn() + slideInVertically(Motion.slideSpring()) { -it },
                    exit = fadeOut() + slideOutVertically { -it }) {
                    shownVoice?.let { VoiceStatus(it) }
                }
                val background = color(backgroundColor)
                val fade = with(density) { ComposerFade.toPx() }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { translationY = size.height * composerShift.value }
                    // Phones: messages fade out just above the composer and never show around it.
                    // Not in voice chat, whose glow behind the controls would otherwise be cut off at the fade.
                    .then(if (floating && voice == null) Modifier.background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background), endY = fade)) else Modifier)
                    .then(if (floating) Modifier.padding(top = ComposerFade) else Modifier)
                    .onFocusChanged { composerFocused = it.hasFocus }.padding(horizontal = Spacing.space16).padding(bottom = if (wide) Spacing.space10 else Spacing.space16), horizontalAlignment = Alignment.CenterHorizontally) {
                    LaunchedEffect(state.unsent) { state.unsent?.let { if (draft.text.isBlank()) draft.setTextAndPlaceCursorAtEnd(it) } }
                    var dictated by remember { mutableStateOf<DictationDraft?>(null) }
                    LaunchedEffect(state.dictation) {
                        val dictation = state.dictation ?: return@LaunchedEffect
                        val current = dictated ?: if (dictation.dictating) DictationDraft(draft.text.toString().trim()) else return@LaunchedEffect
                        if (dictation.text.isNotBlank()) {
                            val next = current.next(draft.text.toString().trim(), dictation.text)
                            draft.setTextAndPlaceCursorAtEnd(next.shown)
                            dictated = next.copy(shown = draft.text.toString().trim())
                        } else dictated = current
                        if (!dictation.dictating) dictated = null
                    }
                    val settings: @Composable RowScope.() -> Unit = {
                        if (state.external) ComposerSettings(state.settings, AgentSettings("", "", "", ""), state.models, {}, actions.onAttach,
                            !state.running && state.chatBlocked == null, photos = state.photos, files = state.files, nativeSettings = false)
                        state.defaults?.takeIf { !state.external }?.let { ComposerSettings(state.settings, it, state.models, actions.onConfigure, actions.onAttach, !state.running, actions.onRefreshModels, state.photos, state.files,
                            agent = state.agent?.name, onAgentDefaults = actions.onSettings.takeIf { state.agent != null }) }
                    }
                    val attachments: @Composable () -> Unit = { if (state.attachments.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.space6), verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                        state.attachments.forEach { a -> key(a.id) { AttachmentChip(a.attachment, a.upload.base64, onPreview = { actions.onPreviewAttachment(a.id) }, onRemove = if (state.running) null else ({ actions.onRemoveAttachment(a.id) })) } }
                    } }
                    val requestNotice: (@Composable () -> Unit)? = if (connectionNotice != null || state.dictation?.error != null) ({
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
                            state.dictation?.error?.let { Text(it, style = TextStyles.secondary, color = color(destructiveColor)) }
                            connectionNotice?.let { (tone, text) -> Text(text, style = TextStyles.secondary, color = tone.color()) }
                        }
                    }) else null
                    AnimatedContent(voice != null, contentAlignment = Alignment.BottomCenter, transitionSpec = {
                        (fadeIn(tween(Motion.reveal, delayMillis = Motion.revealDelay)) + slideInVertically(Motion.cardSpring()) { it / 3 } +
                            scaleIn(Motion.cardSpring(), initialScale = 0.94f))
                            .togetherWith(fadeOut(tween(Motion.fast)) + slideOutVertically(tween(Motion.exit)) { it / 4 })
                            .using(SizeTransform(clip = false))
                    }) { inVoice ->
                    if (inVoice) shownVoice?.let { shown ->
                        RequestComposer(state, actions, requestInputs, Modifier.widthIn(max = ContentWidth), tray = true, notice = requestNotice) {
                            VoiceControls(shown, actions.onPauseVoice, actions.onHoldVoice, actions.onSpeaker, actions.onEndVoice, Modifier.widthIn(max = ContentWidth), settings, attachments)
                        }
                    }
                    else Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8), horizontalAlignment = Alignment.CenterHorizontally) {
                    DelegationFooter(actions.children, actions.agents, actions.onOpenSession, Modifier.widthIn(max = ContentWidth))
                    RequestComposer(state, actions, requestInputs, Modifier.widthIn(max = ContentWidth), notice = requestNotice) {
                        val over = state.ended?.takeIf { it.resume == false && !state.resumeOnSend }
                        if (over != null) EndedComposer(over.blocked, actions.onNew, Modifier.widthIn(max = ContentWidth))
                        // A harness's questions take the composer's place, like the Agent's own requests.
                        else if (state.external && state.ended == null && state.prompts.isNotEmpty() && state.prompts.all { it is ExecutionPrompt.Questions })
                            Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().clip(RoundedCornerShape(Radius.composer)).background(color(fieldColor))
                                .border(1.dp, color(accentColor).copy(alpha = 0.55f), RoundedCornerShape(Radius.composer)).padding(horizontal = Spacing.space18, vertical = Spacing.space14)) {
                                ExternalPrompts(state, actions)
                            }
                        else Composer(
                            running = state.running,
                            onSend = { follow.value = true; actions.onDiscardDictation(); actions.onSend(it) },
                            onStop = actions.onStop,
                            canStop = state.canStop,
                            stopWhileSending = state.external && state.canStop,
                            placeholder = "Chat with ${state.agent?.name ?: "Kinuvo"}",
                            hint = if (wide && state.items.isEmpty()) "Shift+Enter for new line · Markdown formats as you type" + if (state.skills) " · / for skills" else "" else null,
                            blocked = if (state.resumeOnSend) null else state.ended?.blocked ?: state.chatBlocked,
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
                            backdrop = if (composerBlurred) Modifier.hazeBlur(HazeInput.Sources(haze), blur) else Modifier,
                            onVoice = actions.onVoice?.let { start -> { follow.value = true; start() } },
                            notice = if ((state.external && (state.prompts.isNotEmpty() || (state.chatBlocked != null && state.ended == null))) || connectionNotice != null || state.dictation?.error != null) ({
                                state.dictation?.error?.let { Text(it, style = TextStyles.secondary, color = color(destructiveColor)) }
                                connectionNotice?.let { (tone, text) -> Text(text, style = TextStyles.secondary, color = tone.color()) }
                                if (state.external) ExternalPrompts(state, actions)
                            }) else null,
                            onDictate = actions.onDictate,
                            dictating = state.dictation?.dictating == true,
                        )
                    }
                    }
                    }
                }
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
private fun EndedComposer(message: String, onNew: () -> Unit, modifier: Modifier) {
    Panel(modifier.fillMaxWidth(), shape = Theme[shapes][largeShape], contentPadding = PaddingValues(Spacing.space16)) {
        Text(message, style = TextStyles.label, color = color(mutedColor))
        Spacer(Modifier.height(Spacing.space12))
        Button(onNew, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Primary) { Text("New Session") }
    }
}
