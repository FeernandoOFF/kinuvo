package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.board.*
import kinuvo.app.knowledge.*
import kinuvo.app.navigation.*
import kinuvo.app.artifacts.*
import kinuvo.ui.connection.*
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
    // Server-wide settings live under any Agent's Settings; open them under the first.
    val serverSettings = { section: SettingsSection ->
        appState.agents.firstOrNull()?.let { history.open(Route.Settings(it.id, section), push = true) }
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
            val roomsVm = if (connection is Connection.Ready) koinViewModel<kinuvo.app.rooms.RoomsViewModel>(key = "rooms/${app.serverLifetime}") else null
            val roomsState = roomsVm?.state?.collectAsState()?.value ?: kinuvo.app.rooms.RoomsState()
            LaunchedEffect(roomsVm, appState.agents) { roomsVm?.refresh() }
            val shellState = ShellState(appState.agents, agent, appState.sessions.lists, appState.sessions.finished, top,
                ServerMenuState(appState.servers, (connection as? Connection.Ready)?.server, appState.pairing, appState.pairingBase, appState.pairingError, (connection as? Connection.Ready)?.client?.lastConnect?.management == true && appState.capabilities.supports(ServerFeature.Pairing)), appState.capabilities, appState.routines,
                (connection as? Connection.Ready)?.client?.lastConnect?.management != false, roomsState)
            // Phones slide the screens aside to reveal the shell; anything picked there covers it again.
            fun cover(action: () -> Unit) = { action(); shell.revealed = false }
            val shellActions = ShellActions(
                onSelectAgent = { id -> if (top is Route.Rooms) history.open(app.lastView(id)) else app.selectAgent(id, backStack.lastOrNull(), compact, history::open) },
                onAddAgent = cover { history.open(Route.NewAgent) },
                onNew = cover { if (top !is Route.Chat || top.sessionId != null || top.agentId != agent?.id) history.open(Route.Chat(agent?.id, compose = true)) },
                onSelect = { id -> cover { history.open(Route.Chat(agent?.id, id)) }() },
                onLoadMore = { agent?.id?.let(app::loadMoreSessions) },
                onSettings = cover { agent?.let { history.open(Route.Settings(it.id), push = true) } },
                onRooms = { roomsVm?.creating(false); roomsVm?.refresh(); history.open(Route.Rooms) },
                onSelectRoom = { room -> cover { roomsVm?.creating(false); roomsVm?.select(room); history.open(Route.Rooms) }() },
                onRoomSession = { session -> cover { roomsVm?.creating(false); roomsVm?.open(session); history.open(Route.Rooms) }() },
                onNewRoom = cover { roomsVm?.creating(true); history.open(Route.Rooms) },
                onBoard = cover { history.open(Route.Board) },
                onKnowledge = cover { history.open(Route.Knowledge()) },
                onArtifacts = cover { history.open(Route.Artifacts()) },
                onComputers = cover { history.open(Route.Computers()) },
                onSelectServer = { server -> cover { app.use(server); history.reset(Route.Chat()) }() },
                onAddServer = cover(app::addServer),
                onServerSettings = { server -> cover { agent?.let { history.open(Route.Settings(it.id, SettingsSection.Connection, item = server.key), push = true) } }() },
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
            val withShell = top !is Route.Settings && top != Route.Connect && top != Route.NewAgent && (top as? Route.Artifacts)?.fullScreen != true
            val beside = !compact && shell.open && withShell
            val windowChrome = LocalWindowChrome.current
            val screens = @Composable {
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
                                ChatScreen(app, session, !compact, shell, onOpen = history::open,
                                    onSettings = { history.open(Route.Settings(it), push = true) }, onNavigate = history::open, showComputerIndicator = false)
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
                        entry<Route.Rooms> {
                            roomsVm?.let { vm -> kinuvo.ui.rooms.RoomsScreen(appState.agents, vm, history::open, onMenu = { if (compact) shell.revealed = true else shell.open = !shell.open }, onBack = back.takeIf { history.canGoBack }, onAgentSettings = { history.open(Route.Settings(it), push = true) }) }
                        }
                        entry<Route.Board> {
                            BoardScreen(app, koinViewModel<BoardViewModel>(key = "board/${app.serverLifetime}"), onBack = back.takeIf { history.canGoBack }, onOpenSession = history::open,
                                onSettings = { serverSettings(SettingsSection.Tasks) })
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
            CompositionLocalProvider(LocalHistory provides history, LocalPopovers provides popovers) {
                val blur by animateDpAsState(if (shell.quickChat || popovers.active) 16.dp else 0.dp)
                val screenInsets = if (top == Route.NewAgent || top == Route.Connect) WindowInsets(0) else WindowInsets.safeDrawing
                Column(Modifier.fillMaxSize()) {
                    if (connection is Connection.Ready) SessionComputerIndicator(koinInject<ConnectedServer>())
                    Box(Modifier.weight(1f)) {
                        if (compact) Box(Modifier.fillMaxSize()) {
                            Box(Modifier.blur(blur)) {
                                Reveal(shell.revealed, { shell.revealed = it }, behind = { Shell(shellState, shellActions, shell, Modifier.fillMaxSize(), fill = true) },
                                    enabled = withShell) { Box(Modifier.windowInsetsPadding(screenInsets)) { screens() } }
                            }
                            androidx.compose.animation.AnimatedVisibility(shell.quickChat, enter = fadeIn(), exit = fadeOut()) {
                                QuickChat(appState.agents, agent, shell.draft, shellActions.onSelectAgent, shellActions.onQuickSend) { shell.quickChat = false }
                            }
                        }
                        else Row(Modifier.fillMaxSize().windowInsetsPadding(screenInsets).blur(blur)) {
                            AnimatedVisibility(beside, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                                Row { Shell(shellState, shellActions, shell); VerticalSeparator() }
                            }
                            Box(Modifier.weight(1f)) {
                                CompositionLocalProvider(LocalWindowChrome provides windowChrome?.let { if (beside) it.trailingOnly else it }) { screens() }
                            }
                        }
                        popovers.Host()
                    }
                }
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
