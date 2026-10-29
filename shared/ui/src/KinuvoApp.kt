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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
        // One for the whole first run: connecting a Server mustn't start it over, as one keyed by the Server would.
        HarnessTheme { OnboardingScreen(app, koinViewModel<OnboardingViewModel>(key = "first-run")) }
        return
    }
    val connection = appState.connection
    val backStack = rememberSerializable(serializer = SnapshotStateListSerializer(Route.serializer())) {
        mutableStateListOf(initialRoute ?: app.startRoute)
    }
    val ahead = rememberSerializable(serializer = SnapshotStateListSerializer(Route.serializer())) { mutableStateListOf() }
    val history = remember(backStack, ahead) { History(backStack, ahead) }
    val homeEdits = remember { SettingsEdits() }
    val back = { homeEdits.leave { if (history.canGoBack) history.back() else if (backStack.lastOrNull().let { it is Route.Settings }) history.reset(app.lastView()) } }
    val shell = rememberShellChrome()
    BrowserBackHandler()
    val dispatchBack = rememberBackDispatch()
    val shortcutBack = { if (!dispatchBack()) back() }
    val popovers = remember { Popovers() }
    val scope = rememberCoroutineScope()
    val settingsPages = rememberSerializable(serializer = SnapshotStateListSerializer(Route.Settings.serializer())) { mutableStateListOf<Route.Settings>() }
    val inspectorEdits = remember { SettingsEdits() }
    val inspectorPages = rememberSerializable(serializer = SnapshotStateListSerializer(Route.Settings.serializer())) { mutableStateListOf<Route.Settings>() }
    // Phones show Settings in a sheet; Home's pages, such as Routines, are screens like any other.
    val openSettings: (Route.Settings, Boolean) -> Unit = { route, push ->
        homeEdits.leave {
            if (history.compact && !route.home) settingsPages.navigate(route, push) else history.open(route, push)
        }
    }

    // The Agent settings beside the screens, apart from the back stack so the Session under it never changes.
    val inspector = if (connection is Connection.Ready) koinViewModel<AgentInspectorViewModel>(key = "inspector/${app.serverLifetime}") else null
    val inspecting = inspector?.state?.collectAsState()?.value
    // What waits while unsaved Agent settings are settled, run once the inspector lets the host leave.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val guardLeave = { action: () -> Unit ->
        val leave = { if (inspector == null || inspector.leave()) action() else pendingLeave = action }
        if (inspecting?.dirty == true) leave() else inspectorEdits.leave(leave)
    }
    // Server-wide settings live under any Agent's Settings; open them under the first.
    val serverSettings = { section: SettingsSection ->
        appState.agents.firstOrNull()?.let { guardLeave { openSettings(Route.Settings(it.id, section), true) } }
    }
    LaunchedEffect(inspecting?.agentId) { inspectorPages.clear() }
    LaunchedEffect(app.serverLifetime) { settingsPages.clear(); inspectorPages.clear() }
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
                ServerMenuState(appState.servers, (connection as? Connection.Ready)?.server), appState.capabilities, appState.routines,
                (connection as? Connection.Ready)?.client?.lastConnect?.management != false, appState.home)
            // Home's Routines and Tools open on the shown Agent, or the first that Kinuvo runs itself.
            val homePage = { section: SettingsSection ->
                (agent?.takeIf { !it.external } ?: appState.agents.firstOrNull { !it.external })?.let { guardLeave { history.switchTo(Route.Settings(it.id, section)) } }
            }
            val inspect = { id: String -> if (inspector == null) openSettings(Route.Settings(id), true) else { inspector.inspect(id); Unit } }
            // Phones slide the screens aside to reveal the shell; anything picked there covers it again.
            fun cover(action: () -> Unit) = { homeEdits.leave { action(); shell.revealed = false } }
            val shellActions = ShellActions(
                onSelectAgent = { id -> homeEdits.leave { app.selectAgent(id, backStack.lastOrNull(), compact, history::switchTo) } },
                onAddAgent = cover { guardLeave { history.open(Route.NewAgent) } },
                // Phones switch the column, like picking an Agent; wide layouts open Today beside it.
                onHome = { homeEdits.leave { app.showHome(); if (!compact) history.open(Route.Home) } },
                onToday = cover { history.switchTo(Route.Home) },
                onAgentSettings = cover { agent?.let { inspect(it.id) } },
                onOpenSession = { agentId, id -> cover { history.switchTo(Route.Chat(agentId, id)) }() },
                onNew = cover { if (top !is Route.Chat || top.sessionId != null || top.agentId != agent?.id) history.switchTo(Route.Chat(agent?.id, compose = true)) },
                onSelect = { id -> cover { history.switchTo(Route.Chat(agent?.id, id)) }() },
                onLoadMore = { agent?.id?.let(app::loadMoreSessions) },
                onSettings = cover { guardLeave { agent?.let { openSettings(Route.Settings(it.id), true) } } },
                onBoard = cover { history.switchTo(Route.Board) },
                onKnowledge = cover { history.switchTo(Route.Knowledge()) },
                onArtifacts = cover { history.switchTo(Route.Artifacts()) },
                onComputers = cover { history.switchTo(Route.Computers()) },
                onRoutines = cover { homePage(SettingsSection.Routines) },
                onTools = cover { homePage(SettingsSection.Hub) },
                onSelectServer = { server -> cover { guardLeave { app.use(server); history.reset(Route.Chat()) } }() },
                onAddServer = cover { guardLeave(app::addServer) },
                onServerSettings = { server -> cover { guardLeave { agent?.let { openSettings(Route.Settings(it.id, SettingsSection.Connection, item = server.key), true) } } }() },
                onRenameServer = app::rename,
                onForgetServer = { app.forget(it) },
                onQuickSend = { agentId, text, open ->
                    val id = app.quickSend(agentId, text)
                    if (id != null && open) cover { history.switchTo(Route.Chat(agentId, id)) }()
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
            val withShell = (top !is Route.Settings || !compact || top.home) && top != Route.Connect && top != Route.NewAgent && (top as? Route.Artifacts)?.fullScreen != true
            val inspectorBeside = !compact && inspecting?.open == true
            // Narrow windows fold the column while the inspector is open, keeping the rail and room for the screen.
            val foldColumn = inspectorBeside && maxWidth < 1100.dp
            val beside = !compact && (shell.open || top is Route.Settings && !top.home) && withShell
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
                                onRoutine = { agentId, id -> guardLeave { openSettings(Route.Settings(agentId, SettingsSection.Routines, id), true) } },
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
                                onCancel = appState.returnTo?.let { { app.cancelAddServer() } },
                                from = appState.returnTo,
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
                        entry<Route.Settings>(metadata = { if (it.home) emptyMap() else SettingsTransitions(compact) }) { route ->
                            val settings = koinViewModel<SettingsViewModel>(key = "settings/${app.serverLifetime}/${route.agentId}") { parametersOf(route) }
                            key(route, homeEdits.revision) {
                                CompositionLocalProvider(LocalSettingsEdits provides homeEdits) {
                                    SettingsScreen(app, settings, onNavigate = openSettings, onBack = back,
                                        onOpenSession = { next -> homeEdits.leave { history.open(next) } })
                                }
                            }
                        }
                        entry<Route.NewAgent> {
                            NewAgentScreen(app, koinViewModel<OnboardingViewModel>(key = "onboarding/${app.serverLifetime}"), onClose = back) { id ->
                                backStack.remove(Route.NewAgent)
                                app.showAgent(id)
                                history.open(Route.Chat(id))
                            }
                        }
                        entry<Route.Board> {
                            BoardScreen(app, koinViewModel<BoardViewModel>(key = "board/${app.serverLifetime}"), onOpenSession = history::open,
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
                            else kinuvo.ui.computer.ComputersScreen(koinViewModel<kinuvo.app.computer.ComputersViewModel>(key = "computers/${app.serverLifetime}") { parametersOf(client, route) },
                                onOpen = { history.open(Route.Computers(it)) }, onBack = back.takeIf { history.canGoBack && route.computer != null })
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
            val inspectorSavedPages = key(inspecting?.agentId) { rememberSaveableStateHolder() }
            val inspectorSection: (@Composable () -> Unit)? = inspectorPages.lastOrNull()?.let { route ->
                @Composable {
                    inspectorSavedPages.SaveableStateProvider(route.toString()) {
                        DisposableEffect(route) {
                            inspectorEdits.onDiscard = { inspectorSavedPages.removeState(route.toString()) }
                            onDispose { inspectorEdits.onDiscard = {} }
                        }
                        val settings = rememberSettingsViewModel(route)
                        CompositionLocalProvider(LocalSettingsEdits provides inspectorEdits, LocalModalNavigation provides ModalNavigation({ inspectorEdits.leave { inspectorPages.removeLastOrNull() } })) {
                            SettingsScreen(app, settings,
                                onNavigate = { r, push -> inspectorEdits.leave { inspectorPages.navigate(r, push) } },
                                onBack = { inspectorEdits.leave { inspectorPages.removeLastOrNull(); Unit } }, onOpenSession = history::open, sidebar = false)
                        }
                    }
                }
            }
            val inspectorHost = InspectorHost(appState.agents, appState.capabilities,
                (appState.connection as? Connection.Ready)?.let(::serverLabel) ?: "this Server",
                onSection = { id, section -> inspectorPages.add(Route.Settings(id, section)) },
                section = inspectorSection,
                onSectionBack = if (inspectorPages.isEmpty()) null else ({ inspectorEdits.leave { inspectorPages.removeLastOrNull(); Unit } }),
                onCloseRequest = { if (inspecting?.dirty == true) inspector?.close() else inspectorEdits.leave { inspector?.close() } })
            val homeChrome = HomeChrome({ if (compact) shell.revealed = true else shell.open = !shell.open }, !compact)
            CompositionLocalProvider(LocalHistory provides history, LocalPopovers provides popovers, LocalHomeChrome provides homeChrome) {
                val blur by animateDpAsState(if (shell.quickChat || popovers.active) 16.dp else 0.dp)
                val screenInsets = if (top == Route.NewAgent || top == Route.Connect) WindowInsets(0) else WindowInsets.safeDrawing
                val guardSemantics = if (inspecting?.guard != null || inspectorEdits.pending != null || homeEdits.pending != null) Modifier.clearAndSetSemantics {} else Modifier
                val sheetSemantics = if (inspecting?.open == true || settingsPages.isNotEmpty()) Modifier.clearAndSetSemantics {} else Modifier
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
                        // Clear of the window's leading edge, so its title bar keeps no room for the window buttons there.
                        Row { VerticalSeparator(); inspector?.let { CompositionLocalProvider(LocalWindowChrome provides windowChrome?.trailingOnly) { AgentInspector(it, inspectorHost, sheet = false, showGuard = false) } } }
                    }
                }
                if (settingsPages.isNotEmpty()) SettingsSheet(app, settingsPages, history::open)
                inspector?.let { AgentInspectorGuard(it, inspectorHost) }
                SettingsDiscardDialog(homeEdits)
                SettingsDiscardDialog(inspectorEdits)
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
