package kinuvo.ui

import kinuvo.app.workflow.WorkflowViewModel
import kinuvo.app.workflow.WorkflowsViewModel
import kinuvo.app.team.NewTeamViewModel
import kinuvo.ui.workflow.WorkflowScreen
import kinuvo.ui.workflow.WorkflowsScreen
import kinuvo.ui.workflow.WorkflowsSection
import kinuvo.ui.workflow.LocalWorkflows
import kinuvo.app.team.TeamRepository
import kinuvo.app.team.TeamViewModel
import kinuvo.ui.team.TeamEditor
import kinuvo.ui.team.TeamScreen
import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.settings.*
import kinuvo.app.onboarding.*
import kinuvo.ui.agent.*
import kinuvo.ui.settings.*
import kinuvo.ui.onboarding.*
import kinuvo.app.board.*
import kinuvo.app.knowledge.*
import kinuvo.app.navigation.*
import kinuvo.app.artifacts.*
import kinuvo.app.home.*
import kinuvo.ui.connection.*
import kinuvo.ui.home.*
import kinuvo.ui.session.*
import kinuvo.ui.board.*
import kinuvo.ui.knowledge.*
import kinuvo.ui.artifacts.*
import kinuvo.ui.navigation.*

import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.ArrowLeft

import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.capability.supports
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
import kinuvo.protocol.device.PairLink
import kinuvo.protocol.device.Links
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.execution.ExecutionKind
import kinuvo.protocol.session.SessionId
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
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import kinuvo.ui.voice.LiveCallHost
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
import com.composables.icons.lucide.Minimize2
import com.composables.icons.lucide.Server
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import com.composeunstyled.TooltipHost
import kinuvo.ui.design.*
import kinuvo.app.screen.LiveViewModel
import kinuvo.app.panel.MinPanelWidth
import kinuvo.app.panel.PanelTab
import kinuvo.app.panel.SidePanelViewModel
import kinuvo.protocol.screen.ScreenRef
import kinuvo.ui.panel.*
import kinuvo.ui.navigation.PageAction
import kinuvo.ui.navigation.RailWidth
import kinuvo.ui.navigation.ColumnWidth
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Shapes
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.X
import kinuvo.ui.screen.ComputersScreen
import kinuvo.ui.screen.LiveViewScreen

@Composable
fun KinuvoApp(app: AppViewModel = koinViewModel(), initialRoute: Route? = null, manageActivity: Boolean = true) {
    val appState by app.state.collectAsState()
    if (appState.onboarding) {
        // One for the whole first run: connecting a Server mustn't start it over, as one keyed by the Server would.
        KinuvoTheme { OnboardingScreen(app, koinViewModel<OnboardingViewModel>(key = "first-run-${appState.firstRun}")) }
        return
    }
    val connection = appState.connection
    val backStack = rememberSerializable(serializer = SnapshotStateListSerializer(Route.serializer())) {
        mutableStateListOf(initialRoute ?: app.startRoute)
    }
    val ahead = rememberSerializable(serializer = SnapshotStateListSerializer(Route.serializer())) { mutableStateListOf() }
    val history = remember(backStack, ahead) { History(backStack, ahead) }
    val homeEdits = remember { SettingsEdits() }
    // The window's Side panel: its tabs outlive the screens beside them, so a Browser stays live across Sessions.
    val panel = koinViewModel<SidePanelViewModel>(key = "panel/${app.serverLifetime}")
    val panelState by panel.state.collectAsState()
    val panelStores = rememberPanelStores(app.serverLifetime)
    // The Screen a tab was last asked to show, and why; Artifact titles as their tabs load them, and which hold a draft.
    val screenFocus = remember(app.serverLifetime) { mutableStateMapOf<PanelTab, Pair<ScreenRef, String?>>() }
    val artifactTitles = remember(app.serverLifetime) { mutableStateMapOf<String, String>() }
    val editingArtifacts = remember(app.serverLifetime) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(panelState.tabs) {
        panelStores.retain(panelState.tabs)
        screenFocus.keys.retainAll(panelState.tabs.toSet())
        editingArtifacts.keys.retainAll(panelState.tabs.filterIsInstance<PanelTab.Artifact>().map { it.artifact }.toSet())
    }
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
    // Phones show Settings in a sheet; Home's pages, such as Routines, are screens like any other, and what they open goes in the sheet.
    val openSettings: (Route.Settings, Boolean) -> Unit = { route, push ->
        homeEdits.leave {
            if (history.compact && (!route.home || route.item != null || route.editing)) settingsPages.navigate(route, push) else history.open(route, push)
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
    LaunchedEffect(inspecting?.open) { if (inspecting?.open == true) panel.open(PanelTab.AgentSettings) else panel.close(PanelTab.AgentSettings) }
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
    val accent by animateColorAsState(agent?.ink ?: AvatarInk[0], tween(Motion.accent))
    KinuvoTheme(accent = agent?.let { accent }) { TooltipHost(Modifier.fillMaxSize()) {
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
                    Key.Comma -> agent?.let { guardLeave { openSettings(Route.Settings(it.id), true) } } ?: return@onPreviewKeyEvent false
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
            val teamRepository = koinInject<TeamRepository>()
            val teams by teamRepository.state.collectAsState()
            val shellState = ShellState(appState.agents, agent, appState.sessions.lists, appState.sessions.finished, top,
                ServerMenuState(appState.servers, (connection as? Connection.Ready)?.server), appState.capabilities, appState.routines,
                (connection as? Connection.Ready)?.client?.lastConnect?.management != false, appState.home, teams.values.map { it.team }.sortedBy { it.name.lowercase() }, appState.sessions.settled)
            // Home's Routines and Tools open on the shown Agent, or the first that Kinuvo runs itself.
            val homePage = { section: SettingsSection ->
                (agent?.takeIf { !it.external } ?: appState.agents.firstOrNull { !it.external })?.let { guardLeave { history.switchTo(Route.Settings(it.id, section)) } }
            }
            // Home shows no Side panel, so its Agent settings open in Settings.
            val inspect = { id: AgentId ->
                if (inspector == null || !compact && top is Route.Home) openSettings(Route.Settings(id), true)
                else { inspector.inspect(id); panel.open(PanelTab.AgentSettings) }
            }
            // Phones slide the screens aside to reveal the shell; anything picked there covers it again.
            fun cover(action: () -> Unit) = { homeEdits.leave { action(); shell.revealed = false } }
            val consent = koinInject<AiConsent>()
            var asking by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
            AiConsentPrompt(asking != null, asking?.first?.let(::providerName)) { allow -> asking?.second?.complete(allow); asking = null }
            val shellActions = ShellActions(
                onSelectAgent = { id -> homeEdits.leave { app.selectAgent(id, backStack.lastOrNull(), compact, history::switchTo) } },
                onAddAgent = cover { guardLeave { history.open(Route.NewAgent) } },
                // Phones switch the column, like picking an Agent; wide layouts open Today beside it.
                onHome = { homeEdits.leave { app.showHome(); if (!compact) history.open(Route.Home) } },
                onToday = cover { history.switchTo(Route.Home) },
                onAgentSettings = cover { agent?.let { inspect(it.id) } },
                onOpenSession = { agentId, id -> cover { history.switchTo(Route.Chat(agentId, id)) }() },
                onNew = cover { if (top !is Route.Chat || top.sessionId != null || top.agentId != agent?.id) history.switchTo(Route.Chat(agent?.id, compose = true)) },
                onSelect = { id -> cover { history.switchTo(Route.Chat(agent?.id, SessionId(id))) }() },
                onLoadMore = { agent?.id?.let(app::loadMoreSessions) },
                onSettle = app::settle,
                onSettings = cover { guardLeave { agent?.let { openSettings(Route.Settings(it.id), true) } } },
                onBoard = cover { history.switchTo(Route.Board) },
                onTeam = { id -> cover { history.switchTo(Route.Team(id)) }() },
                onKnowledge = cover { history.switchTo(Route.Knowledge()) },
                onArtifacts = cover { history.switchTo(Route.Artifacts()) },
                onComputers = cover { history.switchTo(Route.Computers()) },
                onRoutines = cover { if (appState.agents.any { !it.external }) homePage(SettingsSection.Routines) else history.switchTo(Route.Workflows()) },
                onWorkflows = cover { history.switchTo(Route.Workflows()) },
                onTools = cover { homePage(SettingsSection.Hub) },
                onSelectServer = { server -> cover { guardLeave { app.use(server); history.reset(Route.Chat()) } }() },
                onAddServer = cover { guardLeave(app::addServer) },
                onServerSettings = { server -> cover { guardLeave { agent?.let { openSettings(Route.Settings(it.id, SettingsSection.Connection, item = server.key), true) } } }() },
                onRenameServer = app::rename,
                onForgetServer = { app.forget(it) },
                onQuickSend = send@{ agentId, text, open ->
                    val provider = app.provider(agentId)
                    if (consent.needed(provider)) {
                        if (!CompletableDeferred<Boolean>().also { asking = provider to it }.await()) return@send false
                        consent.grant(provider)
                    }
                    val id = app.quickSend(agentId, text)
                    if (id != null && open) cover { history.switchTo(Route.Chat(agentId, id)) }()
                    id != null
                },
            )
            SideEffect {
                pick = { i, agents ->
                    if (agents) shellState.agents.getOrNull(i)?.let { shellActions.onSelectAgent(it.id) }
                    else (shellState.favorites + shellState.inbox).getOrNull(i)?.let { shellActions.onSelect(it.id.value) }
                }
            }
            // Settings keeps the rail on wide layouts, its sections taking the column's place; on phones it covers the screen.
            val withShell = (top !is Route.Settings || !compact || top.home) && top != Route.Connect && top != Route.NewAgent && (top as? Route.Artifacts)?.fullScreen != true
            val panelShown = !compact && withShell && top !is Route.Home && panelState.tabs.isNotEmpty()
            val beside = !compact && (shell.open || top is Route.Settings && !top.home) && withShell
            val room = maxWidth - (if (beside) RailWidth else 0.dp) - ChatMinWidth - StripWidth - 8.dp
            // Narrow windows fold the column while the Side panel is expanded, keeping the rail and room for the screen.
            val foldColumn = panelShown && !panelState.collapsed && beside && room - ColumnWidth < MinPanelWidth.dp
            // The chat gives up its minimum before the panel narrows past its own.
            val panelMax = (room - if (beside && !foldColumn) ColumnWidth else 0.dp).coerceAtLeast(MinPanelWidth.dp)
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
                            mapOf(AgentKey to route.agentId?.value.orEmpty()) +
                                if (route.compose && route.sessionId == null) NavDisplay.transitionSpec { NoTransition } else emptyMap()
                        }) { route ->
                            if (connection !is Connection.Ready) Loading(connection)
                            else {
                                val session = koinViewModel<SessionViewModel>(key = "session/${app.serverLifetime}") { parametersOf(route) }
                                val resolved by session.state.collectAsState()
                                LaunchedEffect(resolved.route) {
                                    val active = backStack.lastOrNull() as? Route.Chat
                                    val update = if (active?.slot != route.slot) resolved.route else app.resolveView(route, resolved.route)
                                    update?.let(backStack::update)
                                }
                                val browser = route.agentId?.let { PanelTab.Browser(it, route.sessionId) }
                                val dock = if (compact) null else PanelDock(
                                    browserOpen = browser != null && panelState.showing(browser),
                                    toggleBrowser = { browser?.let(panel::toggle) },
                                    showScreen = { ref, reason ->
                                        val tab = ref.owner?.let { PanelTab.Browser(it.agentId, route.sessionId) } ?: PanelTab.Computer(ref.id)
                                        screenFocus[tab] = ref to reason
                                        panel.open(tab)
                                    },
                                    openArtifact = { panel.open(PanelTab.Artifact(it)) },
                                )
                                ChatScreen(app, session, !compact, shell, onOpen = history::open, onSettings = inspect, onNavigate = history::open,
                                    onBack = back.takeIf { backStack.getOrNull(backStack.lastIndex - 1)?.home == true }, dock = dock)
                            }
                        }
                        entry<Route.Settings>(metadata = { if (it.home) emptyMap() else SettingsTransitions(compact) }) { route ->
                            val settings = koinViewModel<SettingsViewModel>(key = "settings/${app.serverLifetime}/${route.agentId}") { parametersOf(route) }
                            // Home's Routines page lists the Server's Workflows too.
                            val workflows = if (route.home && route.section == SettingsSection.Routines) {
                                val list = koinViewModel<WorkflowsViewModel>(key = "workflows/${app.serverLifetime}")
                                val listed by list.state.collectAsState()
                                // Leaving a routine being edited asks first, as other navigation from here does.
                                WorkflowsSection(listed.workflows.takeUnless { it.isEmpty() && listed.loading }, onOpen = { id -> homeEdits.leave { history.open(Route.Workflows(id)) } },
                                    onNew = { opened -> homeEdits.leave { list.create(route.agentId, opened) } },
                                    error = listed.error.takeIf { listed.workflows.isEmpty() }, onReload = list::reload,
                                    // Beside the list on wide windows; phones keep the graph on its own screen.
                                    detail = if (history.compact) null else { id, back, close ->
                                        val workflow = koinViewModel<WorkflowViewModel>(key = "workflow/${app.serverLifetime}/$id") { parametersOf(id) }
                                        WorkflowScreen(workflow, appState.agents,
                                            onOpenSession = { agent, session -> homeEdits.leave { history.open(Route.Chat(agent, session)) } },
                                            onDelete = { workflow.delete(close) }, onBack = back)
                                    })
                            } else null
                            key(route, homeEdits.revision) {
                                CompositionLocalProvider(LocalSettingsEdits provides homeEdits, LocalWorkflows provides workflows) {
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
                        entry<Route.Team> { route ->
                            val id = route.id
                            if (connection !is Connection.Ready) Loading(connection)
                            else if (id == null) {
                                val creating = koinViewModel<NewTeamViewModel>(key = "new-team/${app.serverLifetime}")
                                val created by creating.state.collectAsState()
                                Column(Modifier.fillMaxSize().background(color(backgroundColor)), horizontalAlignment = Alignment.CenterHorizontally) {
                                    PageHeader(Lucide.Users, "New Team", back = back.takeIf { history.canGoBack })
                                    TeamEditor(appState.agents, null, Modifier.widthIn(max = 560.dp).padding(Spacing.space16)) { spec ->
                                        creating.create(spec) { backStack.remove(route); history.open(Route.Team(it)) }
                                    }
                                    created.error?.let { Text(it, Modifier.padding(horizontal = Spacing.space16), color = color(destructiveColor)) }
                                }
                            } else TeamScreen(koinViewModel<TeamViewModel>(key = "team/${app.serverLifetime}/$id") { parametersOf(id) }, appState.agents,
                                onOpenSession = { agentId, session -> history.open(Route.Chat(agentId, session)) }, onBack = back.takeIf { compact && history.canGoBack })
                        }
                        entry<Route.Board> {
                            BoardScreen(app, koinViewModel<BoardViewModel>(key = "board/${app.serverLifetime}"), onOpenSession = history::open,
                                onSettings = { serverSettings(SettingsSection.Tasks) }, onOpenTask = { history.open(Route.Task(it)) })
                        }
                        entry<Route.Artifacts> { route ->
                            if (connection !is Connection.Ready) Loading(connection)
                            else {
                                val artifacts = koinViewModel<ArtifactsViewModel>(key = "artifacts/${app.serverLifetime}/${route.artifact}") { parametersOf(route) }
                                ArtifactsScreen(app, artifacts, ArtifactsActions(
                                    onBack = back.takeIf { history.canGoBack },
                                    onOpen = { history.open(Route.Artifacts(it)) },
                                    onSearch = artifacts::search,
                                    onFilter = artifacts::filter,
                                    onDelete = { id -> artifacts.delete(id); if (id == route.artifact) history.open(Route.Artifacts()) },
                                    onOpenSession = { agent, session -> history.open(Route.Chat(agent, session)) },
                                    onEdit = artifacts::edit,
                                    onChange = artifacts::change,
                                    onSave = artifacts::save,
                                    onCancel = artifacts::cancel,
                                    onKeepMine = artifacts::keepMine,
                                    onSaveDesign = artifacts::saveDesign,
                                    onFullScreen = { if (it) history.open(route.copy(fullScreen = true)) else if (history.canGoBack) back() else history.reset(route.copy(fullScreen = false)) },
                                ))
                            }
                        }
                        entry<Route.Workflows> { route ->
                            val id = route.id
                            if (id == null) {
                                val list = koinViewModel<WorkflowsViewModel>(key = "workflows/${app.serverLifetime}")
                                val listed by list.state.collectAsState()
                                WorkflowsScreen(listed.workflows, appState.agents, onOpen = { history.open(Route.Workflows(it)) }, onBack = back.takeIf { history.canGoBack }, onNew = {
                                    val agent = appState.agents.firstOrNull() ?: return@WorkflowsScreen
                                    list.create(agent.id) { history.open(Route.Workflows(it)) }
                                })
                            } else {
                                val workflow = koinViewModel<WorkflowViewModel>(key = "workflow/${app.serverLifetime}/$id") { parametersOf(id) }
                                WorkflowScreen(workflow, appState.agents,
                                    onOpenSession = { agent, session -> history.open(Route.Chat(agent, session)) },
                                    onDelete = { workflow.delete { if (history.canGoBack) back() else history.reset(Route.Workflows()) } },
                                    onBack = back.takeIf { history.canGoBack })
                            }
                        }
                        entry<Route.Browser> { route ->
                            val client = (connection as? Connection.Ready)?.client
                            if (client == null) Loading(connection)
                            else if (appState.agents.firstOrNull { it.id == route.agentId }?.executionKind == ExecutionKind.Remote) {
                                Column(Modifier.fillMaxSize()) {
                                    PageHeader(Lucide.Globe, "Browser", back = { back() })
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text("This Agent's Server manages its own Browser.")
                                    }
                                }
                            } else {
                                val live = koinViewModel<LiveViewModel>(key = "live/${app.serverLifetime}/$route") { parametersOf(route) }
                                val showChat = !compact && maxWidth >= 1200.dp
                                val chat = Route.Chat(route.agentId, route.sessionId)
                                val dock = PageAction(Lucide.Minimize2, "Dock beside chat") {
                                    panel.open(PanelTab.Browser(route.agentId, route.sessionId))
                                    val previous = backStack.getOrNull(backStack.lastIndex - 1) as? Route.Chat
                                    if (previous?.agentId == route.agentId && previous.sessionId == route.sessionId) back() else history.open(chat)
                                }
                                Row(Modifier.fillMaxSize()) {
                                    if (showChat) {
                                        val session = koinViewModel<SessionViewModel>(key = "live-chat/${app.serverLifetime}/${route.agentId}/${route.sessionId}") { parametersOf(chat) }
                                        Box(Modifier.width(360.dp).fillMaxHeight()) {
                                            ChatScreen(app, session, false, shell, onOpen = history::open, onSettings = inspect, onNavigate = history::open,
                                                dock = PanelDock(true, {
                                                    panel.close(PanelTab.Browser(route.agentId))
                                                    val previous = backStack.getOrNull(backStack.lastIndex - 1) as? Route.Chat
                                                    if (previous?.agentId == route.agentId && previous.sessionId == route.sessionId) back() else history.open(chat)
                                                }, live::focus, { panel.open(PanelTab.Artifact(it)) }))
                                        }
                                        VerticalSeparator()
                                    }
                                    Box(Modifier.weight(1f)) { LiveViewScreen(live, back.takeIf { compact && history.canGoBack }, extra = if (compact) emptyList() else listOf(dock)) }
                                }
                            }
                        }
                        entry<Route.Computers> { route ->
                            if (connection !is Connection.Ready) Loading(connection)
                            else ComputersScreen(koinViewModel<LiveViewModel>(key = "live/${app.serverLifetime}/$route") { parametersOf(route) },
                                list = route.computer == null, onOpen = { history.open(Route.Computers(it.id)) }, back = back.takeIf { history.canGoBack }, server = connection.server.label)
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
            val closeTab = { tab: PanelTab ->
                when {
                    tab == PanelTab.AgentSettings -> inspectorHost.onCloseRequest?.invoke() ?: Unit
                    // A draft is saved or cancelled first: the tab is shown instead.
                    tab is PanelTab.Artifact && editingArtifacts[tab.artifact] == true -> panel.open(tab)
                    else -> panel.close(tab)
                }
            }
            val expand = { tab: PanelTab, route: Route -> panel.close(tab); history.open(route) }
            val panelLabel = { tab: PanelTab ->
                when (tab) {
                    PanelTab.AgentSettings -> PanelTabLabel(Lucide.SlidersHorizontal, "Agent settings")
                    is PanelTab.Browser -> PanelTabLabel(Lucide.Globe, "Browser · ${appState.agents.firstOrNull { it.id == tab.agentId }?.name ?: tab.agentId.value}")
                    is PanelTab.Computer -> PanelTabLabel(Lucide.Monitor, "Computer")
                    is PanelTab.Artifact -> PanelTabLabel(Lucide.Shapes, artifactTitles[tab.artifact] ?: "Artifact")
                }
            }
            val panelTab: @Composable (PanelTab) -> Unit = { tab ->
                when (tab) {
                    PanelTab.AgentSettings -> inspector?.let { AgentInspector(it, inspectorHost, sheet = false, Modifier.fillMaxWidth(), showGuard = false) }
                    is PanelTab.Browser, is PanelTab.Computer -> {
                        val route = requireNotNull(tab.route)
                        val live = koinViewModel<LiveViewModel>(viewModelStoreOwner = panelStores.owner(tab), key = "live") { parametersOf(route) }
                        val focus = screenFocus[tab]
                        LaunchedEffect(live, focus) { focus?.let { live.focus(it.first, it.second) } }
                        LiveViewScreen(live, null, docked = true, extra = listOf(
                            PageAction(Lucide.Maximize2, if (tab is PanelTab.Browser) "Expand Browser" else "Expand Computer") {
                                expand(tab, if (route is Route.Browser) route.copy(screen = live.state.value.selected) else Route.Computers(live.state.value.selected?.id))
                            },
                            PageAction(Lucide.X, if (tab is PanelTab.Browser) "Close Browser" else "Close Computer") { panel.close(tab) },
                        ))
                    }
                    is PanelTab.Artifact -> {
                        val artifacts = koinViewModel<ArtifactsViewModel>(viewModelStoreOwner = panelStores.owner(tab), key = "artifact") { parametersOf(Route.Artifacts(tab.artifact)) }
                        val artifactState by artifacts.state.collectAsState()
                        val title = artifactState.open?.info?.title
                        LaunchedEffect(title) { title?.let { artifactTitles[tab.artifact] = it } }
                        val editing = artifactState.draft != null
                        LaunchedEffect(editing) { editingArtifacts[tab.artifact] = editing }
                        ArtifactsScreen(app, artifacts, ArtifactsActions(
                            onDelete = { id -> artifacts.delete(id); panel.close(tab) },
                            onOpenSession = { agent, session -> history.open(Route.Chat(agent, session)) },
                            onEdit = artifacts::edit,
                            onChange = artifacts::change,
                            onSave = artifacts::save,
                            onCancel = artifacts::cancel,
                            onKeepMine = artifacts::keepMine,
                            onSaveDesign = artifacts::saveDesign,
                            onFullScreen = { expand(tab, Route.Artifacts(tab.artifact, fullScreen = it)) },
                            onClose = { panel.close(tab) },
                            onExpand = { expand(tab, Route.Artifacts(tab.artifact)) },
                        ))
                    }
                }
            }
            val homeChrome = HomeChrome({ if (compact) shell.revealed = true else shell.open = !shell.open }, !compact)
            val strips = remember { Strips() }
            CompositionLocalProvider(LocalHistory provides history, LocalPopovers provides popovers, LocalHomeChrome provides homeChrome, LocalStrips provides strips) {
                // Phones show the Agent's settings as sheets over the screen; wider windows show them beside it, unblurred.
                val sheet = compact && (inspecting?.open == true || settingsPages.isNotEmpty())
                val blur by animateDpAsState(if (LocalBackdropBlur.current && (shell.quickChat || popovers.active || sheet)) 16.dp else 0.dp)
                val edgeToEdge = top == Route.NewAgent || top == Route.Connect
                val screenInsets = if (!edgeToEdge) WindowInsets.safeDrawing else WindowInsets(0)
                val guardSemantics = if (inspecting?.guard != null || inspectorEdits.pending != null || homeEdits.pending != null) Modifier.clearAndSetSemantics {} else Modifier
                val sheetSemantics = if (inspecting?.open == true || settingsPages.isNotEmpty()) Modifier.clearAndSetSemantics {} else Modifier
                if (compact) Box(Modifier.fillMaxSize().then(guardSemantics)) {
                    StripFrame(strips, (if (blur > 0.dp) Modifier.blur(blur) else Modifier).then(sheetSemantics)) {
                        Reveal(shell.revealed, { shell.revealed = it }, behind = { Shell(shellState, shellActions, shell, Modifier.fillMaxSize(), fill = true) },
                            enabled = withShell) { Box(Modifier.windowInsetsPadding(screenInsets)) { screens() } }
                    }
                    AnimatedVisibility(shell.quickChat, modifier = sheetSemantics, enter = fadeIn(), exit = fadeOut()) {
                        QuickChat(appState.agents, agent, shell.draft, shellActions.onSelectAgent, shellActions.onQuickSend) { shell.quickChat = false }
                    }
                    inspector?.let { AgentInspector(it, inspectorHost, sheet = true, showGuard = false) }
                }
                else StripFrame(strips, (if (blur > 0.dp) Modifier.blur(blur) else Modifier).then(guardSemantics)) { Row(Modifier.fillMaxSize().windowInsetsPadding(screenInsets)) {
                    AnimatedVisibility(beside, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                        Row { Shell(shellState, shellActions, shell, column = !foldColumn); VerticalSeparator() }
                    }
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalWindowChrome provides windowChrome?.let { if (panelShown) (if (beside) it.inner else it.leadingOnly) else if (beside) it.trailingOnly else it }) { screens() }
                    }
                    AnimatedVisibility(panelShown, enter = expandHorizontally(expandFrom = Alignment.Start), exit = shrinkHorizontally(shrinkTowards = Alignment.Start)) {
                        // The strip takes the window's trailing edge; tabs beside it keep no room for window buttons.
                        CompositionLocalProvider(LocalWindowChrome provides windowChrome?.trailingOnly) {
                            SidePanel(panelState, panel.actions(onClose = closeTab), panelMax, panelLabel) { tab ->
                                CompositionLocalProvider(LocalWindowChrome provides windowChrome?.inner) { panelTab(tab) }
                            }
                        }
                    }
                } }
                if (settingsPages.isNotEmpty()) SettingsSheet(app, settingsPages, history::open)
                inspector?.let { AgentInspectorGuard(it, inspectorHost) }
                SettingsDiscardDialog(homeEdits)
                SettingsDiscardDialog(inspectorEdits)
                StripHost(strips)
                popovers.Host()
                LiveCallHost(appState.agents)
                (connection as? Connection.Ready)?.client?.let { client ->
                    val linked by client.state.collectAsState()
                    ConnectionStrip(linked, appState.syncedAt)
                }
            }
            appState.notice?.let { notice ->
                Row(Modifier.align(Alignment.BottomCenter).padding(Spacing.space16), verticalAlignment = Alignment.CenterVertically) {
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
        Spinner(size = 24.dp)
        val server = (connection as? Connection.Connecting)?.server
        Text(server?.let { "Connecting to ${it.label}…" } ?: "Connecting…", color = color(mutedColor), style = TextStyles.secondary)
    }
}

/** The chat beside the Side panel keeps room for its messages and composer. */
private val ChatMinWidth = 560.dp
