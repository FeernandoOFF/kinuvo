package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.board.*
import kinuvo.app.knowledge.*
import kinuvo.app.navigation.*
import kinuvo.app.artifacts.*
import kinuvo.app.home.*
import kinuvo.ui.connection.*
import kinuvo.ui.home.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.knowledge.*
import kinuvo.ui.artifacts.*
import kinuvo.ui.navigation.*

import kinuvo.protocol.ServerFeature
import kinuvo.protocol.supports
import kinuvo.protocol.privateNetworkHost
import kinuvo.protocol.requireSecureTransport

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kinuvo.protocol.PairLink
import kinuvo.protocol.Links
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.draw.blur
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.navigation3.scene.Scene
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import kotlinx.coroutines.launch
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSerializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.savedstate.compose.serialization.serializers.SnapshotStateListSerializer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Server
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import com.composeunstyled.TooltipHost
import kinuvo.ui.design.*

@Composable
fun KinuvoApp(app: AppViewModel = koinViewModel(), initialRoute: Route? = null, manageActivity: Boolean = true) {
    val appState by app.state.collectAsState()
    if (appState.onboarding) {
        HarnessTheme { OnboardingScreen(app, koinViewModel<OnboardingViewModel>(key = "onboarding/${app.serverLifetime}")) }
        return
    }
    val connection = appState.connection
    val backStack = rememberSerializable(serializer = SnapshotStateListSerializer(Route.serializer())) {
        mutableStateListOf(initialRoute ?: app.startRoute)
    }
    val ahead = rememberSerializable(serializer = SnapshotStateListSerializer(Route.serializer())) { mutableStateListOf() }
    val history = remember(backStack, ahead) { History(backStack, ahead) }
    val back = { if (history.canGoBack) history.back() else if (backStack.lastOrNull().let { it is Route.Settings }) history.reset(app.lastView()) }
    val shell = rememberShellChrome()
    BrowserBackHandler()
    val dispatchBack = rememberBackDispatch()
    val shortcutBack = { if (!dispatchBack()) back() }
    val popovers = remember { Popovers() }
    val scope = rememberCoroutineScope()
    // The Agent settings beside the screens, apart from the back stack so the Session under it never changes.
    val inspector = if (connection is Connection.Ready) koinViewModel<AgentInspectorViewModel>(key = "inspector/${app.serverLifetime}") else null
    val inspecting = inspector?.state?.collectAsState()?.value
    // What waits while unsaved Agent settings are settled, run once the inspector lets the host leave.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val guardLeave = { action: () -> Unit -> if (inspector == null || inspector.leave()) action() else pendingLeave = action }
    // Server-wide settings live under any Agent's Settings; open them under the first.
    val serverSettings = { section: SettingsSection ->
        appState.agents.firstOrNull()?.let { guardLeave { history.open(Route.Settings(it.id, section), push = true) } }
    }
    LaunchedEffect(inspecting?.left) {
        if (inspecting?.left == true) {
            pendingLeave?.invoke()
            pendingLeave = null
            inspector?.leaveHandled()
        }
    }
    val focus = remember { FocusRequester() }
    // Cmd+1…9 opens the shown Agent's nth chat; with Shift, the nth Agent. Set once the shell's state is known.
    var pick by remember { mutableStateOf<(index: Int, agent: Boolean) -> Unit>({ _, _ -> }) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(connection) {
        when (connection) {
            is Connection.Unpaired -> if (backStack.lastOrNull() != Route.Connect) history.reset(Route.Connect)
            is Connection.Ready -> if (backStack.lastOrNull() == Route.Connect) history.reset(app.lastView())
            is Connection.Connecting -> {}
        }
    }
    if (manageActivity) (connection as? Connection.Ready)?.client?.let { client ->
        LifecycleStartEffect(client) {
            client.setActive(true)
            onStopOrDispose { client.setActive(false) }
        }
    }
    val link by pendingLink.collectAsState()
    LaunchedEffect(link, connection is Connection.Ready) {
        val route = link ?: return@LaunchedEffect
        if (connection !is Connection.Ready) return@LaunchedEffect
        pendingLink.value = null
        history.open(app.withShownAgent(route))
    }
    // Keeps focus in the app when the focused screen or field goes away, e.g. the composer when Settings opens, so the
    // app's shortcuts keep working.
    LaunchedEffect(backStack.lastOrNull(), focused) { if (!focused) focus.requestFocus() }
    LaunchedEffect(backStack) {
        snapshotFlow { backStack.lastOrNull() }.collect { r -> r?.let(app::showView) }
    }

    // Tooltips draw in this host, over everything else.
    val agent = appState.agents.firstOrNull { it.id == appState.agentId } ?: appState.agents.firstOrNull()
    val accent by animateColorAsState(agent?.ink ?: AvatarInk[0], tween(500))
    HarnessTheme(accent = agent?.let { accent }) { TooltipHost(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier
            .fillMaxSize()
            .background(color(backgroundColor))
            .dismissMobileKeyboard()
            .onFocusChanged { focused = it.hasFocus }
            .focusRequester(focus)
            .focusable()
            .mouseHistoryButtons(onBack = shortcutBack, onForward = history::forward)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isShortcutPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.LeftBracket -> shortcutBack()
                    Key.RightBracket -> history.forward()
                    Key.Period -> if (e.isShiftPressed) return@onPreviewKeyEvent false else shell.open = !shell.open
                    in NumberKeys -> pick(NumberKeys.indexOf(e.key), e.isShiftPressed)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }) {
            // Phones slide screens in and out the platform's way, following the back gesture; wide layouts swap in place.
            val compact = maxWidth < 720.dp
            SideEffect { history.compact = compact }
            val top = backStack.lastOrNull()
            LaunchedEffect(agent?.id) { if (app.state.value.agentId == null) agent?.id?.let(app::showAgent) }
            val shellState = ShellState(appState.agents, agent, appState.sessions.lists, appState.sessions.finished, top,
                ServerMenuState(appState.servers, (connection as? Connection.Ready)?.server, appState.pairing, appState.pairingBase, appState.pairingError, (connection as? Connection.Ready)?.client?.lastConnect?.management == true && appState.capabilities.supports(ServerFeature.Pairing)), appState.capabilities, appState.routines,
                (connection as? Connection.Ready)?.client?.lastConnect?.management != false, appState.home)
            val inspect = { id: String -> if (inspector == null) history.open(Route.Settings(id), push = true) else { inspector.inspect(id); Unit } }
            // Phones slide the screens aside to reveal the shell; anything picked there covers it again.
            fun cover(action: () -> Unit) = { action(); shell.revealed = false }
            val shellActions = ShellActions(
                onSelectAgent = { id -> app.selectAgent(id, backStack.lastOrNull(), compact, history::open) },
                onAddAgent = cover { guardLeave { history.open(Route.NewAgent) } },
                // Phones switch the column, like picking an Agent; wide layouts open Today beside it.
                onHome = { app.showHome(); if (!compact) history.open(Route.Home) },
                onToday = cover { history.open(Route.Home) },
                onAgentSettings = cover { agent?.let { inspect(it.id) } },
                onOpenSession = { agentId, id -> cover { history.open(Route.Chat(agentId, id)) }() },
                onNew = cover { if (top !is Route.Chat || top.sessionId != null || top.agentId != agent?.id) history.open(Route.Chat(agent?.id, compose = true)) },
                onSelect = { id -> cover { history.open(Route.Chat(agent?.id, id)) }() },
                onLoadMore = { agent?.id?.let(app::loadMoreSessions) },
                onSettings = cover { guardLeave { agent?.let { history.open(Route.Settings(it.id), push = true) } } },
                onBoard = cover { history.open(Route.Board) },
                onKnowledge = cover { history.open(Route.Knowledge()) },
                onArtifacts = cover { history.open(Route.Artifacts()) },
                onComputers = cover { history.open(Route.Computers()) },
                onSelectServer = { server -> cover { guardLeave { app.use(server); history.reset(Route.Chat()) } }() },
                onAddServer = cover { guardLeave(app::addServer) },
                onServerSettings = { server -> cover { guardLeave { agent?.let { history.open(Route.Settings(it.id, SettingsSection.Connection, item = server.key), push = true) } } }() },
                onRefreshPairing = app::refreshPairing,
                onPairingBase = app::selectPairingBase,
                onRenameServer = app::rename,
                onForgetServer = { app.forget(it) },
                onQuickSend = { agentId, text, open ->
                    val id = app.quickSend(agentId, text)
                    if (id != null && open) cover { history.open(Route.Chat(agentId, id)) }()
                    id != null
                },
            )
            SideEffect {
                pick = { i, agents ->
                    if (agents) shellState.agents.getOrNull(i)?.let { shellActions.onSelectAgent(it.id) }
                    else (shellState.favorites + shellState.chats).getOrNull(i)?.let { shellActions.onSelect(it.id) }
                }
            }
            // Settings keeps the rail on wide layouts, its sections taking the column's place; on phones it covers the screen.
            val withShell = (top !is Route.Settings || !compact) && top != Route.Connect && top != Route.NewAgent && (top as? Route.Artifacts)?.fullScreen != true
            val inspectorBeside = !compact && inspecting?.open == true
            // Narrow windows fold the column while the inspector is open, keeping the rail and room for the screen.
            val foldColumn = inspectorBeside && maxWidth < 1100.dp
            val beside = !compact && (shell.open || top is Route.Settings) && withShell
            val windowChrome = LocalWindowChrome.current
            val content = @Composable {
                NavDisplay(
                    backStack = backStack,
                    onBack = back,
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                    // Switching Agents fades instead of pushing: it's a different place, not a level deeper.
                    transitionSpec = { if (!compact) NoTransition else if (switchesAgent()) Fade else Push() },
                    popTransitionSpec = { if (!compact) NoTransition else if (switchesAgent()) Fade else Pop() },
                    // Back gestures slide like a pop, without the platform default's shrinking.
                    predictivePopTransitionSpec = { if (compact) Pop() else NoTransition },
                    entryProvider = entryProvider {
                        entry<Route.Home> {
                            if ((connection as? Connection.Ready)?.client == null) Loading(connection)
                            else HomeScreen(app, koinViewModel<HomeViewModel>(key = "home/${app.serverLifetime}") { parametersOf(app) }, HomeActions(
                                onOpenSession = { agentId, id -> history.open(Route.Chat(agentId, id)) },
                                onOpenTask = { history.open(Route.Task(it)) },
                                onBoard = { history.open(Route.Board) },
                                onRoutine = { agentId, id -> guardLeave { history.open(Route.Settings(agentId, SettingsSection.Routines, id), push = true) } },
                                onToggleShell = { if (compact) shell.revealed = true else shell.open = !shell.open },
                                onBack = back.takeIf { history.canGoBack },
                            ), !compact)
                        }
                        entry<Route.Task> { route ->
                            TaskScreen(app, koinViewModel<BoardViewModel>(key = "board/${app.serverLifetime}"), route.id, back.takeIf { history.canGoBack }, history::open)
                        }
                        entry<Route.Connect> {
                            ConnectScreen(
                                error = (connection as? Connection.Unpaired)?.error,
                                connecting = (connection as? Connection.Connecting)?.let { it.server ?: Server.Embedded },
                                onConnect = app::pair,
                                servers = appState.servers,
                                onSelectServer = app::use,
                                initialUrl = appState.pendingPair?.server ?: (connection as? Connection.Unpaired)?.server?.url ?: koinInject<Device>().serverUrl,
                                pending = appState.pendingPair,
                                initialName = (connection as? Connection.Unpaired)?.server?.name.orEmpty(),
                                onRenameServer = app::rename,
                                onForgetServer = { app.forget(it) },
                                onCancel = appState.returnTo?.let { { app.cancelAddServer() } },
                            )
                        }
                        entry<Route.Chat>(clazzContentKey = { it.slot }, metadata = { route ->
                            mapOf(AgentKey to route.agentId.orEmpty()) +
                                if (route.compose && route.sessionId == null) NavDisplay.transitionSpec { NoTransition } else emptyMap()
                        }) { route ->
                            val client = (connection as? Connection.Ready)?.client
                            if (client == null) Loading(connection)
                            else {
                                val session = koinViewModel<SessionViewModel>(key = "session/${app.serverLifetime}") { parametersOf(client, route) }
                                val resolved by session.state.collectAsState()
                                LaunchedEffect(resolved.route) {
                                    val active = backStack.lastOrNull() as? Route.Chat
                                    val update = if (active?.slot != route.slot) resolved.route else app.resolveView(route, resolved.route)
                                    update?.let(backStack::update)
                                }
                                ChatScreen(app, session, !compact, shell, onOpen = history::open, onSettings = inspect, onNavigate = history::open,
                                    onBack = back.takeIf { backStack.getOrNull(backStack.lastIndex - 1)?.home == true })
                            }
                        }
                        entry<Route.Settings>(metadata = { SettingsTransitions(compact) }) { route ->
                            val settings = koinViewModel<SettingsViewModel>(key = "settings/${app.serverLifetime}/${route.agentId}") { parametersOf(route) }
                            SettingsScreen(
                                app, settings,
                                onNavigate = history::open,
                                onBack = back,
                                onOpenSession = history::open,
                            )
                        }
                        entry<Route.NewAgent> {
                            NewAgentScreen(app, koinViewModel<OnboardingViewModel>(key = "onboarding/${app.serverLifetime}"), onClose = back) { id ->
                                backStack.remove(Route.NewAgent)
                                app.showAgent(id)
                                history.open(Route.Chat(id))
                            }
                        }
                        entry<Route.Board> {
                            BoardScreen(app, koinViewModel<BoardViewModel>(key = "board/${app.serverLifetime}"), onBack = back.takeIf { history.canGoBack }, onOpenSession = history::open,
                                onSettings = { serverSettings(SettingsSection.Tasks) }, onOpenTask = { history.open(Route.Task(it)) })
                        }
                        entry<Route.Artifacts> { route ->
                            val client = (connection as? Connection.Ready)?.client
                            if (client == null) Loading(connection)
                            else {
                                val artifacts = koinViewModel<ArtifactsViewModel>(key = "artifacts/${app.serverLifetime}/${route.artifact}") { parametersOf(client, route) }
                                ArtifactsScreen(app, artifacts, ArtifactsActions(
                                    onBack = back.takeIf { history.canGoBack },
                                    onOpen = { history.open(Route.Artifacts(it)) },
                                    onSearch = artifacts::search,
                                    onFilter = artifacts::filter,
                                    onDelete = { id -> artifacts.delete(id); if (id == route.artifact) history.open(Route.Artifacts()) },
                                    onOpenSession = { agent, session -> history.open(Route.Chat(agent, session)) },
                                    onFullScreen = { if (it) history.open(route.copy(fullScreen = true)) else if (history.canGoBack) back() else history.reset(route.copy(fullScreen = false)) },
                                ))
                            }
                        }
                        entry<Route.Computers> { route ->
                            val client = (connection as? Connection.Ready)?.client
                            if (client == null) Loading(connection)
                            else kinuvo.ui.computer.ComputersScreen(koinViewModel<kinuvo.app.computer.ComputersViewModel>(key = "computers/${app.serverLifetime}") { parametersOf(client, route) }, back.takeIf { history.canGoBack })
                        }
                        entry<Route.Knowledge> { route ->
                            val knowledge = koinViewModel<KnowledgeViewModel>(key = "knowledge/${app.serverLifetime}/${route.note}") { parametersOf(route) }
                            KnowledgeScreen(app, knowledge, onOpen = history::open, onBack = back.takeIf { history.canGoBack },
                                onSettings = { serverSettings(SettingsSection.Knowledge) })
                        }
                    },
                )
            }
            // Moved, not rebuilt, when rotating or resizing across the compact width, so open screens keep running, e.g. voice chat.
            val current by rememberUpdatedState(content)
            val screens = remember { movableContentOf { current() } }
            val inspectorHost = InspectorHost(appState.agents, appState.capabilities, (appState.connection as? Connection.Ready)?.let(::serverLabel) ?: "this Server") { id, section ->
                guardLeave { history.open(Route.Settings(id, section), push = true) }
            }
            CompositionLocalProvider(LocalHistory provides history, LocalPopovers provides popovers) {
                val blur by animateDpAsState(if (shell.quickChat || popovers.active) 16.dp else 0.dp)
                val screenInsets = if (top == Route.NewAgent || top == Route.Connect) WindowInsets(0) else WindowInsets.safeDrawing
                val guardSemantics = if (inspecting?.guard != null) Modifier.clearAndSetSemantics {} else Modifier
                val sheetSemantics = if (inspecting?.open == true) Modifier.clearAndSetSemantics {} else Modifier
                if (compact) Box(Modifier.fillMaxSize().then(guardSemantics)) {
                    Box(Modifier.blur(blur).then(sheetSemantics)) {
                        Reveal(shell.revealed, { shell.revealed = it }, behind = { Shell(shellState, shellActions, shell, Modifier.fillMaxSize(), fill = true) },
                            enabled = withShell) { Box(Modifier.windowInsetsPadding(screenInsets)) { screens() } }
                    }
                    AnimatedVisibility(shell.quickChat, modifier = sheetSemantics, enter = fadeIn(), exit = fadeOut()) {
                        QuickChat(appState.agents, agent, shell.draft, shellActions.onSelectAgent, shellActions.onQuickSend) { shell.quickChat = false }
                    }
                    inspector?.let { AgentInspector(it, inspectorHost, sheet = true, showGuard = false) }
                }
                else Row(Modifier.fillMaxSize().windowInsetsPadding(screenInsets).blur(blur).then(guardSemantics)) {
                    AnimatedVisibility(beside, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                        Row { Shell(shellState, shellActions, shell, column = !foldColumn); VerticalSeparator() }
                    }
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalWindowChrome provides windowChrome?.let { if (beside) it.trailingOnly else it }) { screens() }
                    }
                    AnimatedVisibility(inspectorBeside, enter = expandHorizontally(expandFrom = Alignment.Start), exit = shrinkHorizontally(shrinkTowards = Alignment.Start)) {
                        Row { VerticalSeparator(); inspector?.let { AgentInspector(it, inspectorHost, sheet = false, showGuard = false) } }
                    }
                }
                inspector?.let { AgentInspectorGuard(it, inspectorHost) }
                popovers.Host()
            }
            appState.notice?.let { notice ->
                Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(notice, Modifier.weight(1f, fill = false), tone = Tone.Danger, icon = Lucide.CircleX)
                    Button(app::dismissNotice, style = ButtonStyle.Ghost) { Text("Dismiss") }
                }
            }
        }
    } }
}

private val NumberKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

private val NoTransition = ContentTransform(EnterTransition.None, ExitTransition.None)

/**
 * On phones Settings slides up over the screen it's opened from, and back down when it closes.
 * Its own deeper levels push as usual.
 */
private fun SettingsTransitions(compact: Boolean): Map<String, Any> {
    fun AnimatedContentTransitionScope<Scene<*>>.within() = initialState.entries.lastOrNull()?.metadata?.containsKey(SettingsKey) == true &&
        targetState.entries.lastOrNull()?.metadata?.containsKey(SettingsKey) == true
    val open: AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform = {
        when {
            !compact -> NoTransition
            within() -> slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 4 }
            else -> slideInVertically { it } togetherWith ExitTransition.KeepUntilTransitionsFinished
        }
    }
    val close: AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform = {
        when {
            !compact -> NoTransition
            within() -> slideInHorizontally { -it / 4 } togetherWith slideOutHorizontally { it }
            else -> ContentTransform(EnterTransition.None, slideOutVertically { it }, targetContentZIndex = -1f)
        }
    }
    return mapOf(SettingsKey to true) + NavDisplay.transitionSpec(open) + NavDisplay.popTransitionSpec(close) +
        NavDisplay.predictivePopTransitionSpec { close() }
}

private const val SettingsKey = "kinuvo.settings"
private const val AgentKey = "kinuvo.agent"
private val Fade = fadeIn() togetherWith fadeOut()

private fun AnimatedContentTransitionScope<Scene<Route>>.switchesAgent(): Boolean {
    val from = initialState.entries.lastOrNull()?.metadata?.get(AgentKey) as? String
    val to = targetState.entries.lastOrNull()?.metadata?.get(AgentKey) as? String
    return !from.isNullOrEmpty() && !to.isNullOrEmpty() && from != to
}
private val Push = defaultTransitionSpec<Route>()
private val Pop = defaultPopTransitionSpec<Route>()

@Composable
private fun Loading(connection: Connection) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spinner(size = 24.dp)
        val server = (connection as? Connection.Connecting)?.server
        Text(server?.let { "Connecting to ${it.label}…" } ?: "Connecting…", color = color(mutedColor), fontSize = 13.sp)
    }
}
