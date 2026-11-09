package kinuvo.ui.onboarding

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.app.settings.*
import kinuvo.app.onboarding.*
import kinuvo.ui.agent.*
import kinuvo.ui.settings.*
import kinuvo.ui.session.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.LocalTextStyle
import kinuvo.protocol.*
import kinuvo.app.navigation.allows
import kinuvo.ui.design.*
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import kinuvo.app.navigation.Route
import kinuvo.ui.device.DeviceToolsGroup
private val Phases = listOf(Lucide.House, Lucide.Brain, Lucide.User, Lucide.Smile, Lucide.Sparkles, Lucide.Plug, Lucide.Server, Lucide.Users)
private val PhaseNames = listOf("Home", "Model", "You", "Identity", "Soul", "Connect", "Runs on", "Agents")
private val Headlines = listOf("Where should I live?", "Which mind should I use?", "What should I call you?", "", "How should I show up?", "What can I work with?",
    "Where does your new Agent run?", "Which Agents?")
private val Subtitles = listOf(
    "Pick where your Server runs. You can add more Servers later.",
    "Your model powers every conversation. Each provider decides where your messages are processed.",
    "Optional. I'll keep it in my notes about you.",
    "Swipe or tap to change how I look, then give me a name.",
    "Five choices shape my Soul. You can change it anytime in Settings.",
    "Optional. Each one lets me do more for you; all of them are in Settings later.",
    "Kinuvo's own Agent, or an agent you already use, started by this Server.",
    "Only Agents the other Server shares with linked Servers.",
)
private val TraitIcons = listOf(Lucide.Heart to Lucide.Briefcase, Lucide.Smartphone to Lucide.Newspaper, Lucide.Rocket to Lucide.Users, Lucide.Laugh to Lucide.Glasses, Lucide.Flower to Lucide.Flame)
internal val AvatarInk = listOf(Color(0xFFA98BF0), Color(0xFFFF7A2F), Color(0xFF20C985), Color(0xFFFF6B6B), Color(0xFF2F8CFF), Color(0xFFFFB321), Color(0xFFE0559F), Color(0xFF9C6440))
private val LocalTint = compositionLocalOf { AvatarInk[0] }
private const val SoulStep = 5

internal class OnboardingAction(val label: String, val enabled: Boolean = true, val run: () -> Unit = {})

@Composable
internal fun OnboardingScreen(app: AppViewModel, onboarding: OnboardingViewModel) {
    val a by app.state.collectAsState()
    val s by onboarding.state.collectAsState()
    val ready = a.connection as? Connection.Ready
    val config = org.koin.compose.koinInject<ConfigRepository>()
    val attached by config.connected.collectAsState()
    LaunchedEffect(ready?.client, attached, a.agents.firstOrNull()?.id) {
        onboarding.load(a.agents.firstOrNull().takeIf { ready != null && attached }, keepLook = true)
    }
    val url = rememberTextFieldState(a.pendingPair?.server ?: org.koin.compose.koinInject<Device>().serverUrl)
    val code = rememberTextFieldState(a.pendingPair?.code.orEmpty())
    val alias = rememberTextFieldState()
    var manual by rememberSaveable { mutableStateOf(a.pendingPair != null) }
    LaunchedEffect(a.pendingPair) { a.pendingPair?.let { url.setTextAndPlaceCursorAtEnd(it.server); code.setTextAndPlaceCursorAtEnd(it.code); manual = true } }
    LaunchedEffect(url) {
        snapshotFlow { url.text.toString() }.collect { text -> Links.parsePair(text)?.let {
            url.setTextAndPlaceCursorAtEnd(it.server); code.setTextAndPlaceCursorAtEnd(it.code)
        } }
    }
    var advancing by rememberSaveable { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.details, advancing, ready?.client) {
        if (advancing && ready != null && s.details != null) { onboarding.edit { it.copy(step = 2) }; advancing = false }
    }
    LaunchedEffect(a.connection, s.error) { if (a.connection is Connection.Unpaired || s.error != null) advancing = false }
    // Connecting to a Server only carries on once its Agent has loaded; one already connected carries on right away.
    val connect = { server: Server ->
        scanError = null
        if (ready?.server == server && s.details != null) onboarding.edit { it.copy(step = 2) }
        else { advancing = true; if (ready?.server == server) onboarding.load(a.agents.firstOrNull(), keepLook = true) else app.use(server) }
    }
    val pair = { link: String, pairing: String ->
        scanError = null; advancing = true; app.pair(link, pairing, alias.value())
    }
    val scan = kinuvo.ui.connection.rememberQrScanner { text ->
        val link = Links.parsePair(text)
        if (link == null) scanError = "That QR code isn't a Kinuvo pairing code. Pair a device on your Server and scan the code it shows."
        else pair(link.server, link.code)
    }
    val account = s.providers.firstOrNull { it.id == s.provider }
    val next = { onboarding.edit { it.moved(1) } }
    val action = when {
        s.step == 2 && account?.apiKey != null && s.apiKey.isNotBlank() -> OnboardingAction("Save key") { onboarding.saveKey(s.apiKey) }
        s.step == 2 && account?.account?.signedIn == false && s.deviceCode == null -> OnboardingAction("Sign in to ${account.name}") { onboarding.login() }
        s.step == 2 && account?.account?.signedIn == false -> OnboardingAction("I've signed in") { onboarding.refresh() }
        s.step == 2 && account?.downloads?.downloading != null -> OnboardingAction("Downloading…", false) {}
        s.step == 2 && account?.downloads?.models?.firstOrNull { it.id == s.model }?.let { !it.installed && it.supported } == true ->
            OnboardingAction("Download (${gigabytes(account.downloads!!.models.first { it.id == s.model }.bytes)})") { onboarding.download() }
        s.step == 2 -> OnboardingAction("Continue", s.modelReady, next)
        s.step == 3 && s.userName.isBlank() && s.occupation.isBlank() -> OnboardingAction("Skip for now", run = next)
        s.step == 4 -> OnboardingAction("Continue", s.agentName.isNotBlank(), next)
        s.step == ConnectStep -> OnboardingAction("Meet ${s.agentName.ifBlank { "your Agent" }}") { onboarding.finish(app::finishOnboarding) }
        else -> OnboardingAction("Continue", run = next)
    }
    BrowserBackHandler()
    val dispatchBack = rememberBackDispatch()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(s.step, s.setup, focused) { if (!focused) focus.requestFocus() }
    Box(Modifier.fillMaxSize()
        .onFocusChanged { focused = it.hasFocus }
        .focusRequester(focus)
        .focusable()
        .mouseHistoryButtons(onBack = { dispatchBack() }, onForward = {})
        .onPreviewKeyEvent { e ->
            e.type == KeyEventType.KeyDown && e.isShortcutPressed && e.key == Key.LeftBracket && dispatchBack()
        }) {
        OnboardingContent(s, onEdit = onboarding::edit, onChoice = onboarding::choose, action = action, working = advancing,
            connectionError = (a.connection as? Connection.Unpaired)?.error, welcome = { featured, pokes, poke, tint, insets ->
                Welcome(WelcomeOptions(
                    onScan = scan, onLocal = { connect(Server.Embedded) }.takeIf { Server.Embedded in a.servers },
                    onPair = { pair(url.value(), code.value()) },
                    servers = a.servers.filterIsInstance<Server.Remote>(),
                    onServer = { server -> if (server.token != null) connect(server) else { url.setTextAndPlaceCursorAtEnd(server.url); alias.setTextAndPlaceCursorAtEnd(server.name.orEmpty()); manual = true } },
                    url = url, code = code, name = alias, manual = manual, onManual = { manual = it },
                    busy = advancing || (a.connection as? Connection.Connecting)?.server != null,
                    error = scanError ?: (a.connection as? Connection.Unpaired)?.error,
                ), featured, pokes, poke, tint, insets)
            }, model = { ModelStep(s, onboarding::selectProvider, { onboarding.refresh() }, onboarding::edit, { onboarding.download() }, { onboarding.cancelDownload() }, { id, model -> onboarding.refresh(id, model) }) },
            connect = {
                ConnectList(s, a.capabilities::allows, onboarding::setUp)
                DeviceToolsGroup("On this device", "Agents read these from this app, so they can only use them while Kinuvo is open here.")
            })
        LaunchedEffect(s.step == ConnectStep) { if (s.step == ConnectStep) onboarding.loadConnected() }
        val setup = s.setup
        val agentId = s.details?.info?.id
        if (setup != null && agentId != null) ConnectSetup(app, agentId, setup) { onboarding.setUp(null) }
    }
}

/** The Settings section for [section] over the onboarding, without the other sections. */
@Composable
private fun ConnectSetup(app: AppViewModel, agentId: String, section: kinuvo.app.navigation.SettingsSection, editing: Boolean = false, item: String? = null, onDone: () -> Unit) {
    var route by remember(section) { mutableStateOf(Route.Settings(agentId, section, item, editing = editing)) }
    // The overlay owns its ViewModels: each route's is cleared when the route changes or the overlay closes.
    val owner = remember(route) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val settings = org.koin.compose.viewmodel.koinViewModel<SettingsViewModel>(viewModelStoreOwner = owner) { org.koin.core.parameter.parametersOf(route) }
    val back = { if (route.item != null || route.editing) route = route.copy(item = null, editing = false) else onDone() }
    NavigationBackHandler(androidx.navigationevent.compose.rememberNavigationEventState(NavigationEventInfo.None)) { back() }
    Box(Modifier.fillMaxSize().background(color(backgroundColor)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // No shell beside it, so every page leads with back, clear of the window buttons.
        CompositionLocalProvider(LocalModalNavigation provides ModalNavigation(back)) {
            SettingsScreen(app, settings, onNavigate = { r, _ -> if (r.section != section || editing && r.item != null && !r.editing) onDone() else route = r },
                onBack = back, onOpenSession = {}, sidebar = false)
        }
    }
}

private val ConnectInfo = mapOf(
    kinuvo.app.navigation.SettingsSection.Secrets to Triple(Lucide.KeyRound, "Password manager", "Use passwords and API keys without pasting them into settings."),
    kinuvo.app.navigation.SettingsSection.Knowledge to Triple(Lucide.Library, "Notes", "Where I keep what I learn about your people and projects: built in, or your Obsidian vault or Anytype space."),
    kinuvo.app.navigation.SettingsSection.Tasks to Triple(Lucide.ListChecks, "Tasks", "Your reminders, so I can see and work on your to-dos."),
    kinuvo.app.navigation.SettingsSection.Notifications to Triple(Lucide.Bell, "Notifications", "So I can reach you when something needs you."),
    kinuvo.app.navigation.SettingsSection.Search to Triple(Lucide.Search, "Web search", "Search the web, not just open pages."),
    kinuvo.app.navigation.SettingsSection.Workspace to Triple(Lucide.Terminal, "Shell and files", "This machine, a container or an SSH host where I can run commands."),
    kinuvo.app.navigation.SettingsSection.Mcp to Triple(Lucide.Plug, "Apps and services", "Notion, Linear, GitHub, Google and more."),
)

@Composable
internal fun ConnectList(s: OnboardingState, available: (kinuvo.app.navigation.SettingsSection) -> Boolean, onSetUp: (kinuvo.app.navigation.SettingsSection) -> Unit) {
    RowGroup {
        ConnectSections.forEach { section ->
            val (icon, title, description) = ConnectInfo.getValue(section)
            val enabled = available(section)
            GroupRow(title, { onSetUp(section) }, if (enabled) description else "Not on this Server", icon, enabled = enabled) {
                if (s.connected[section] == true) Pill("On", tone = Tone.Success, icon = Lucide.CircleCheck)
            }
        }
    }
}

/** A new Agent: where it runs, then the onboarding's Identity and Soul steps for Kinuvo's own, or a working folder for an ACP agent. */
@Composable
internal fun NewAgentScreen(app: AppViewModel, onboarding: OnboardingViewModel, onClose: () -> Unit, onCreated: (String) -> Unit) {
    val a by app.state.collectAsState()
    val s by onboarding.state.collectAsState()
    val from = a.agents.firstOrNull { it.id == a.agentId } ?: a.agents.firstOrNull()
    val server = (a.connection as? Connection.Ready)?.let(::serverLabel) ?: "this Server"
    val harnessed = a.capabilities.runsHarnesses
    LaunchedEffect(from?.id) { if (from != null && s.details == null) onboarding.newAgent(from, a.agents, harnessed) }
    LaunchedEffect(harnessed) { if (harnessed) onboarding.offerHarnesses() }
    LaunchedEffect(server, s.steps) { if (s.server != server) onboarding.edit { it.copy(server = server) } }
    /** The Harnesses form open over the flow: a new ACP command (null) or linking a Server. */
    var adding by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    val meet = "Meet ${s.agentName.ifBlank { "your Agent" }}"
    val create = { onboarding.create(a.agents.map { it.id }, onCreated, a.agents); Unit }
    val action = when {
        s.step == HarnessStep -> OnboardingAction("Continue", s.harness == HarnessChoice.Kinuvo || s.connection?.let(::selectable) == true) {
            onboarding.edit { it.moved(1) }
            if (s.harness is HarnessChoice.Remote) onboarding.discover()
        }
        s.step == PickStep -> s.picked.size.let { n -> OnboardingAction(if (n == 1) "Add 1 Agent" else "Add $n Agents", n > 0, create) }
        s.step == 4 && s.harness is HarnessChoice.Acp -> OnboardingAction(meet, s.agentName.isNotBlank() && cwdProblem(s.cwd) == null, create)
        s.step == SoulStep -> OnboardingAction(meet, s.details != null, create)
        else -> OnboardingAction("Continue", s.agentName.isNotBlank()) { onboarding.edit { it.moved(1) } }
    }
    Box(Modifier.fillMaxSize()) {
        OnboardingContent(s, onEdit = onboarding::edit, onChoice = onboarding::choose, action = action, onClose = onClose,
            harness = { HarnessChoices(s, a.capabilities.offers(Integration.Harness, "acp"), a.capabilities.offers(Integration.Harness, "remote"), a.agents, onboarding::edit) { link -> adding = link; open = true } },
            pick = { PickAgents(s, onboarding::edit) })
        val agentId = s.details?.info?.id
        if (open && agentId != null) ConnectSetup(app, agentId, kinuvo.app.navigation.SettingsSection.Harnesses, editing = true, item = adding) { open = false }
    }
}

/** Where a new Agent runs, each harness named with the machine it runs on and its readiness. */
@Composable
internal fun HarnessChoices(s: OnboardingState, acp: Boolean, remote: Boolean, agents: List<AgentInfo>, onEdit: ((OnboardingState) -> OnboardingState) -> Unit,
    /** Opens the Harnesses form: a new ACP command (null), or [LinkItem]. */
    onAdd: (String?) -> Unit) {
    val pick = { choice: HarnessChoice -> onEdit { it.choose(choice) } }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space16)) {
        RowGroup {
            ChoiceRow(Lucide.Sparkles, "Kinuvo on ${s.server}", "Kinuvo's own Agent. You set its Soul, Memory, Skills and tools.", s.harness == HarnessChoice.Kinuvo) { pick(HarnessChoice.Kinuvo) }
        }
        if (s.connections == null) return@Column Box(Modifier.fillMaxWidth().padding(Spacing.space24), contentAlignment = Alignment.Center) { Spinner() }
        RowGroup("ACP agent on ${s.server}") {
            if (!acp) ChoiceRow(Lucide.SquareTerminal, "ACP agent", "ACP commands need a computer Server. ${s.server.replaceFirstChar { it.uppercase() }} can't run them.", false, enabled = false) {}
            else {
                s.connections.orEmpty().filter { it.kind == ExecutionKind.Acp }.forEach { c ->
                    val used = agents.count { it.connectionId == c.config.id }
                    val subtitle = listOfNotNull((c.config.connection as? ExecutionConnection.Acp)?.command?.firstOrNull(), "used by $used Agent${if (used == 1) "" else "s"}".takeIf { used > 0 }).joinToString(" · ")
                    ChoiceRow(Lucide.SquareTerminal, c.config.name, subtitle, s.harness == HarnessChoice.Acp(c.config.id), enabled = selectable(c),
                        trailing = { ReadinessPill(readiness(c, s.server)) }) { pick(HarnessChoice.Acp(c.config.id)) }
                }
                GroupRow("Add an ACP command", { onAdd(null) }, "Claude Code, Codex, Gemini CLI or another ACP agent on ${s.server}.", Lucide.Plus)
            }
        }
        RowGroup("Agents from another Kinuvo Server") {
            if (!remote) ChoiceRow(Lucide.ArrowLeftRight, "Another Kinuvo Server", "Linking Kinuvo Servers isn't available on ${s.server}.", false, enabled = false) {}
            else {
                s.connections.orEmpty().filter { it.kind == ExecutionKind.Remote }.forEach { c ->
                    val used = agents.count { it.connectionId == c.config.id }
                    ChoiceRow(Lucide.ArrowLeftRight, c.config.name, if (used > 0) "$used Agent${if (used == 1) "" else "s"} added" else "Runs Agents it shares with ${s.server}",
                        s.harness == HarnessChoice.Remote(c.config.id), enabled = selectable(c), trailing = { ReadinessPill(readiness(c, s.server)) }) { pick(HarnessChoice.Remote(c.config.id)) }
                }
                GroupRow("Link a Kinuvo Server", { onAdd(LinkItem) }, "With a link code from the other Server's Settings › Harnesses › Share Agents.", Lucide.Link)
            }
        }
    }
}

/** A linked Server's shared Agents to add, each picked explicitly; ones already added stay checked. */
@Composable
internal fun PickAgents(s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit) {
    val shared = s.shared ?: return Box(Modifier.fillMaxWidth().padding(Spacing.space24), contentAlignment = Alignment.Center) { Spinner() }
    val host = s.connection?.config?.host ?: s.connection?.config?.name ?: "The other Server"
    val registrations = s.registrations
    if (shared.isEmpty()) return Text("$host doesn't share any Agents with ${s.server} yet. Pick some in its Settings › Harnesses › Share Agents.", color = color(mutedColor))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        RowGroup { shared.forEach { agent ->
            val added = s.added(agent.id, registrations) != null
            val on = added || agent.id in s.picked
            ChoiceRow(Lucide.Bot, agent.name, if (added) "Added" else "Runs on $host", on, enabled = !added,
                trailing = { if (!added && !agent.readiness.online) ReadinessPill(readiness(agent.readiness.state(), agent.name, host, agent.name)) }) {
                onEdit { it.copy(picked = if (agent.id in it.picked) it.picked - agent.id else it.picked + agent.id) }
            }
        } }
        Text("They run on $host, with its settings. Names already used here get \"($host)\" added; rename them later.", color = color(mutedColor), style = TextStyles.secondary)
    }
}

/** A row of a [RowGroup] to pick one of: its icon turns into a check in the Agent's tint when [selected]. */
@Composable
private fun ChoiceRow(icon: ImageVector, title: String, description: String, selected: Boolean, enabled: Boolean = true, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    val tint = LocalTint.current
    val badge by animateColorAsState(if (selected) tint else color(subtleColor))
    GroupRow(title, onClick, description, enabled = enabled, chevron = false, leading = {
        Box(Modifier.size(32.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
            Icon(if (selected) Lucide.Check else icon, Modifier.size(16.dp), tint = if (selected) Color.White else color(mutedColor))
        }
    }) { trailing() }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun OnboardingContent(
    s: OnboardingState,
    onEdit: ((OnboardingState) -> OnboardingState) -> Unit = {},
    onChoice: (Int, Int) -> Unit = { _, _ -> },
    action: OnboardingAction = OnboardingAction("Continue"),
    onClose: (() -> Unit)? = null,
    working: Boolean = false,
    connectionError: String? = null,
    /** The first step: what Kinuvo is and how to connect, given the featured avatar, its pokes and the tint. */
    welcome: @Composable (featured: Int, pokes: List<Int>, onPoke: (Int) -> Unit, tint: Color, insets: WindowInsets) -> Unit = { _, _, _, _, _ -> },
    model: @Composable () -> Unit = {},
    connect: @Composable () -> Unit = {},
    harness: @Composable () -> Unit = {},
    pick: @Composable () -> Unit = {},
    insets: WindowInsets = WindowInsets.safeDrawing,
) {
    val back = when {
        s.busy -> null
        s.step != s.steps.first() -> { { onEdit { it.moved(-1) } } }
        else -> onClose
    }
    NavigationBackHandler(androidx.navigationevent.compose.rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = back != null) { back?.invoke() }
    val pokes = remember { mutableStateListOf(0, 0, 0, 0) }
    // The Agent introducing itself on the welcome is the one the steps then shape, so coming back starts on it too.
    var featured by remember { mutableIntStateOf(s.avatar.takeIf { it < pokes.size } ?: 0) }
    val feature = { i: Int -> featured = i; pokes[i] += 1; onEdit { it.copy(avatar = i) } }
    // The welcome's Agents take turns introducing themselves until one is tapped.
    var touring by remember { mutableStateOf(true) }
    if (s.step == 0 && touring) LaunchedEffect(featured) { delay(4500); feature((featured + 1) % pokes.size) }
    val tint by animateColorAsState(AvatarInk[if (s.step == 0) featured else s.avatar], tween(Motion.avatar))
    CompositionLocalProvider(LocalTint provides tint) { SharedTransitionLayout {
        // Leaving the welcome, its Agent flies to its place in the steps while the rest crossfades.
        // Each side keeps the state it was shown with, so the steps fading out never see the welcome's step.
        AnimatedContent(s, contentKey = { it.step == 0 }, transitionSpec = { fadeIn(tween(Motion.slow)) togetherWith fadeOut(tween(Motion.standard)) }) { s ->
        CompositionLocalProvider(LocalAgentShare provides AgentShare(this@SharedTransitionLayout, this)) {
        if (s.step == 0) return@CompositionLocalProvider welcome(featured, pokes, { touring = false; feature(it) }, tint, insets)
        Box(Modifier.fillMaxSize().background(color(backgroundColor)).drawBehind {
            val wide = size.width >= 800.dp.toPx()
            val center = if (wide) Offset(size.width * 0.27f, size.height * 0.5f) else Offset(size.width / 2, size.height * 0.2f)
            drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.34f), tint.copy(alpha = 0f)), center, size.minDimension * if (wide) 0.6f else 0.75f))
        }) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(insets).imePadding()) {
                val wide = maxWidth >= 800.dp
                val roomy = maxHeight >= 600.dp
                val gutter = if (wide) Spacing.space48 else Spacing.space22
                Column(Modifier.fillMaxSize().padding(horizontal = gutter, vertical = Spacing.space12)) {
                    OnboardingTopBar(s, onEdit, onClose, gutter)
                    val form: @Composable ColumnScope.() -> Unit = {
                        StepForm(s, onEdit, onChoice, wide, model, connect, harness, pick, Modifier.weight(1f).fillMaxWidth())
                        (s.error ?: connectionError)?.let { Text(it, color = color(destructiveColor), modifier = Modifier.padding(bottom = Spacing.space10)) }
                        Cta(action, s.busy || working, if (wide) Modifier.widthIn(min = 260.dp) else Modifier.fillMaxWidth())
                    }
                    if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.space64), verticalAlignment = Alignment.CenterVertically) {
                        AgentStage(s, onEdit, true, Modifier.weight(1.1f).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = Spacing.space24), content = form)
                    } else {
                        if (roomy) AgentStage(s, onEdit, false, Modifier.fillMaxWidth().height(if (s.step == 4) 230.dp else 190.dp))
                        Column(Modifier.weight(1f), content = form)
                    }
                }
            }
        }
        }
        }
    } }
}

/** Where the onboarding's Agent is drawn, so [sharedAgent] can move it between the welcome and the steps. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class AgentShare(val transition: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

internal val LocalAgentShare = compositionLocalOf<AgentShare?> { null }

/** Marks the onboarding's Agent, which moves to its new place when the welcome and the steps swap. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedAgent(): Modifier {
    val share = LocalAgentShare.current ?: return this
    val state = share.transition.rememberSharedContentState("agent")
    return with(share.transition) { this@sharedAgent.sharedElement(state, share.visibility) }
}

@Composable
private fun OnboardingTopBar(s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit, onClose: (() -> Unit)?, gutter: Dp) {
    val chrome = LocalWindowChrome.current
    // Clear of the desktop window buttons the content draws under, on both sides so the track stays centred.
    val inset = (maxOf(chrome?.leadingInset ?: 0.dp, chrome?.trailingInset ?: 0.dp) - gutter).coerceAtLeast(0.dp)
    Row(Modifier.fillMaxWidth().height(64.dp).padding(top = Spacing.space4).padding(horizontal = inset)) {
        Box(Modifier.width(48.dp).height(38.dp), contentAlignment = Alignment.CenterStart) {
            if (s.step != s.steps.first()) HeaderButton(Lucide.ArrowLeft, "Back", { onEdit { it.moved(-1) } }, enabled = !s.busy)
            else onClose?.let { HeaderButton(Lucide.X, "Cancel", it, enabled = !s.busy) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PhaseTrack(s.steps.filter { it > 0 }, s.step) { step -> if (!s.busy) onEdit { it.copy(step = step, error = null) } }
        }
        Spacer(Modifier.width(Spacing.space48))
    }
}

@Composable
private fun PhaseTrack(phases: List<Int>, step: Int, onJump: (Int) -> Unit) {
    val tint = LocalTint.current
    val ink = color(onBackgroundColor)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            phases.forEachIndexed { index, phase ->
                val icon = Phases[phase - 1]
                val current = phase == step
                val done = index < phases.indexOf(step)
                if (index > 0) {
                    val line by animateColorAsState(if (index <= phases.indexOf(step)) tint else color(borderColor))
                    Box(Modifier.width(12.dp).height(2.dp).background(line))
                }
                val background by animateColorAsState(when { current -> ink; done -> tint.copy(alpha = 0.35f); else -> Color.Transparent })
                val size by animateDpAsState(if (current) 38.dp else 30.dp, Motion.selectionSpring())
                Box(Modifier.size(size).clip(CircleShape).background(background)
                    .then(if (!current && !done) Modifier.border(1.dp, color(borderColor), CircleShape) else Modifier)
                    .then(if (done) Modifier.titleBarControl().clickable(role = Role.Button) { onJump(phase) } else Modifier)
                    .semantics { contentDescription = PhaseNames[phase - 1] }, contentAlignment = Alignment.Center) {
                    Icon(icon, Modifier.size(if (current) 18.dp else 14.dp), tint = when { current -> color(backgroundColor); done -> ink; else -> color(mutedColor) })
                }
            }
        }
        AnimatedContent(PhaseNames[step - 1], transitionSpec = { fadeIn() togetherWith fadeOut() }) { Text(it, style = TextStyles.caption, color = color(mutedColor)) }
    }
}

@Composable
private fun AgentStage(s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit, wide: Boolean, modifier: Modifier) {
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.choices, s.avatar) { poke++ }
    val count = AvatarArtboards.size
    val cycle = { delta: Int -> onEdit { it.copy(avatar = (it.avatar + delta + count) % count) } }
    val size = if (wide) 280.dp else if (s.step == 4) 150.dp else 130.dp
    val gaze = when (s.step) { 4 -> Gaze.Forward; else -> if (wide) Gaze.Right else Gaze.Down }
    val face: @Composable (Dp) -> Unit = { faceSize ->
        AnimatedContent(s.avatar, transitionSpec = {
            val forward = (targetState - initialState + count) % count == 1
            (slideInHorizontally { if (forward) it / 2 else -it / 2 } + fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith
                (slideOutHorizontally { if (forward) -it / 2 else it / 2 } + fadeOut() + scaleOut(targetScale = 0.7f))
        }) { avatar ->
            Floating(AvatarArtboards[avatar], faceSize, gaze, poke, 0f, Modifier.then(if (avatar == s.avatar) Modifier.sharedAgent() else Modifier)
                .pointerInput(Unit) { detectTapGestures { poke++ } }.semantics { contentDescription = AvatarArtboards[avatar] })
        }
    }
    if (s.step == SoulStep && !wide) Row(modifier.padding(vertical = Spacing.space8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space12)) {
        face(110.dp)
        SpeechBubble(voicePreview(s), Modifier.weight(1f), RoundedCornerShape(Radius.bubble, Radius.bubble, Radius.bubble, Radius.small))
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (s.step == 4) {
            var drag by remember { mutableFloatStateOf(0f) }
            Row(Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { if (abs(drag) > 48.dp.toPx()) cycle(if (drag < 0) 1 else -1); drag = 0f }) { _, delta -> drag += delta }
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) Spacing.space8 else Spacing.none)) {
                listOf(-1, 0, 1).forEach { delta ->
                    if (delta == 0) face(size)
                    else Floating(AvatarArtboards[(s.avatar + delta + count) % count], size * 0.42f, if (delta < 0) Gaze.Right else Gaze.Left, 0, delta.toFloat(),
                        Modifier.alpha(0.5f).clip(CircleShape).clickable(role = Role.Button) { cycle(delta) }.semantics { contentDescription = if (delta < 0) "Previous look" else "Next look" })
                }
            }
            Spacer(Modifier.height(Spacing.space12))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarInk.forEachIndexed { index, ink ->
                    val width by animateDpAsState(if (index == s.avatar) 24.dp else 8.dp, Motion.selectionSpring())
                    Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { onEdit { it.copy(avatar = index) } }.padding(5.dp).semantics { contentDescription = AvatarArtboards[index] }) {
                        Box(Modifier.size(width, 8.dp).clip(CircleShape).background(ink))
                    }
                }
            }
        } else face(size)
        if (s.step == SoulStep) {
            Spacer(Modifier.height(Spacing.space20))
            SpeechBubble(voicePreview(s), Modifier.widthIn(max = 380.dp), RoundedCornerShape(Radius.small, Radius.bubble, Radius.bubble, Radius.bubble))
        }
    }
}

@Composable
private fun StepForm(
    s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit, onChoice: (Int, Int) -> Unit, wide: Boolean,
    model: @Composable () -> Unit, connect: @Composable () -> Unit, harness: @Composable () -> Unit, pick: @Composable () -> Unit, modifier: Modifier,
) {
    AnimatedContent(s.step, transitionSpec = {
        val forward = s.steps.indexOf(targetState) > s.steps.indexOf(initialState)
        (fadeIn() + slideInHorizontally { if (forward) it / 6 else -it / 6 }) togetherWith (fadeOut() + slideOutHorizontally { if (forward) -it / 6 else it / 6 })
    }, modifier = modifier) { step ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.space20), verticalArrangement = Arrangement.spacedBy(Spacing.space14)) {
            val display = displayStyle(if (wide) 40.sp else 30.sp)
            if (step == 4) NameHeadline(s, display, onEdit) else Text(Headlines[step - 1], style = display)
            Text(Subtitles[step - 1], color = color(mutedColor))
            Spacer(Modifier.height(Spacing.space2))
            when (step) {
                2 -> model()
                3 -> {
                    DraftField("Your name", s.userName, "Alex") { value -> onEdit { it.copy(userName = value) } }
                    DraftField("What you do", s.occupation, "Product designer, student, retired…") { value -> onEdit { it.copy(occupation = value) } }
                }
                ConnectStep -> connect()
                HarnessStep -> harness()
                PickStep -> pick()
                4 -> (s.harness as? HarnessChoice.Acp)?.let { AcpIdentity(s, onEdit) }
                SoulStep -> {
                    PersonalityChoices.indices.forEach { index -> TraitSwitch(index, s.choices[index]) { onChoice(index, it) } }
                    PromptEditor(s, onEdit)
                }
            }
        }
    }
}

/** An ACP Agent's working folder, and what its harness owns instead of a Soul. */
@Composable
private fun AcpIdentity(s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit) {
    val harness = s.connection?.config?.name ?: "The harness"
    val host = s.connection?.config?.host ?: s.server
    DraftField("Working folder", s.cwd, "/Users/you/project") { value -> onEdit { it.copy(cwd = value) } }
    Text((if (s.cwd.isBlank()) null else cwdProblem(s.cwd)) ?: "On $host. $harness reads and edits files here.",
        color = color(if (s.cwd.isNotBlank() && cwdProblem(s.cwd) != null) destructiveColor else mutedColor), style = TextStyles.secondary)
    Text("Instructions, Memory, Skills and tools come from $harness.", color = color(mutedColor), style = TextStyles.secondary)
}

@Composable
private fun NameHeadline(s: OnboardingState, display: TextStyle, onEdit: ((OnboardingState) -> OnboardingState) -> Unit) {
    val tint = LocalTint.current
    val field = rememberTextFieldState(s.agentName)
    LaunchedEffect(s.agentName) { if (field.text.toString() != s.agentName) field.setTextAndPlaceCursorAtEnd(s.agentName) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { value -> onEdit { it.copy(agentName = value) } } }
    Column {
        Text("Hey ${s.userName.ifBlank { "there" }}, I'm", style = display)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            BasicTextField(field, Modifier.weight(1f).drawBehind {
                drawRect(tint, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
            }.semantics { contentDescription = "Agent name" }, textStyle = display.copy(color = color(onBackgroundColor)), cursorBrush = SolidColor(tint),
                lineLimits = TextFieldLineLimits.SingleLine, keyboardOptions = inputKeyboard(true))
            FieldButton(Lucide.Dices, "Suggest a name", { onEdit { it.copy(agentName = NameIdeas.filter { name -> name != it.agentName }.random()) } })
        }
    }
}

@Composable
private fun TraitSwitch(index: Int, selected: Int, onSelect: (Int) -> Unit) {
    val tint = LocalTint.current
    val thumb by animateFloatAsState(selected.toFloat(), Motion.thumbSpring())
    BoxWithConstraints(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(color(subtleColor))) {
        Box(Modifier.offset(x = maxWidth / 2 * thumb).width(maxWidth / 2).fillMaxHeight().padding(Spacing.space4).clip(CircleShape).background(tint.copy(alpha = 0.4f)))
        Row(Modifier.fillMaxSize()) {
            PersonalityChoices[index].second.forEachIndexed { choice, option ->
                val on = choice == selected
                val ink by animateColorAsState(color(if (on) onBackgroundColor else mutedColor))
                Row(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable(role = Role.RadioButton) { onSelect(choice) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (choice == 0) TraitIcons[index].first else TraitIcons[index].second, Modifier.size(16.dp), tint = ink)
                    Spacer(Modifier.width(Spacing.space8))
                    Text(option, color = ink, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, style = TextStyles.callout)
                }
            }
        }
    }
}

@Composable
private fun PromptEditor(s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.clip(RoundedCornerShape(Radius.medium)).clickable(role = Role.Button) { open = !open }.padding(vertical = Spacing.space8, horizontal = Spacing.space4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Icon(Lucide.Pencil, Modifier.size(15.dp), tint = color(mutedColor))
        Text("Edit the full prompt", color = color(mutedColor), style = TextStyles.label)
        Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, Modifier.size(14.dp), tint = color(mutedColor))
    }
    AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val prompt = rememberTextFieldState(s.prompt)
        LaunchedEffect(s.prompt) { if (prompt.text.toString() != s.prompt) prompt.setTextAndPlaceCursorAtEnd(s.prompt) }
        LaunchedEffect(prompt) { snapshotFlow { prompt.text.toString() }.collect { value -> onEdit { it.copy(prompt = value) } } }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            TextArea(prompt, lines = 8)
            Text("Changing a choice above rebuilds this prompt.", color = color(mutedColor), style = TextStyles.caption)
        }
    }
}

@Composable
internal fun TextAction(action: OnboardingAction, modifier: Modifier) {
    Box(modifier.height(48.dp).clip(CircleShape).clickable(action.enabled, role = Role.Button, onClick = action.run), contentAlignment = Alignment.Center) {
        Text(action.label, color = color(mutedColor), fontWeight = FontWeight.SemiBold, style = TextStyles.callout)
    }
}

@Composable
private fun OnboardingField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) { Text(label, style = TextStyles.secondary, fontWeight = FontWeight.SemiBold); content() }
}

@Composable
private fun DraftField(label: String, value: String, placeholder: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val field = rememberTextFieldState(value)
    LaunchedEffect(value) { if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(onChange) }
    OnboardingField(label) { Input(field, placeholder, secret = secret, capitalize = !secret) }
}

/** A [DraftField] as a row of a [RowGroup]. */
@Composable
private fun DraftRow(label: String, value: String, placeholder: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val field = rememberTextFieldState(value)
    LaunchedEffect(value) { if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(onChange) }
    FieldRow(label) { Input(field, placeholder, secret = secret, capitalize = !secret) }
}

@Composable
internal fun ModelStep(s: OnboardingState, onProvider: (String) -> Unit, onRefresh: () -> Unit, onEdit: ((OnboardingState) -> OnboardingState) -> Unit,
    onDownload: () -> Unit = {}, onCancel: () -> Unit = {}, onEndpoint: ((String, String) -> Unit)? = null) {
    val tint = LocalTint.current
    val group = s.catalog.providers.firstOrNull { it.id == s.provider }
    val account = s.providers.firstOrNull { it.id == s.provider }
    s.providerGroups.forEach { (title, providers) ->
        if (providers.isNotEmpty()) RowGroup(title) {
            if (title == "On device") Row(Modifier.fillMaxWidth().background(color(warningColor).copy(alpha = 0.1f))
                .padding(horizontal = Spacing.space16, vertical = Spacing.space12), horizontalArrangement = Arrangement.spacedBy(Spacing.space12)) {
                Icon(Lucide.TriangleAlert, Modifier.size(24.dp), tint = color(warningColor))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
                    Text("Less capable than cloud models", color = color(warningColor), style = TextStyles.label.copy(fontWeight = FontWeight.SemiBold))
                    Text("Private, but small models can struggle with tools and longer tasks. Running locally uses battery and memory; downloads can be several GB. Use Wi-Fi.", style = TextStyles.secondary)
                }
            }
            providers.forEach { id ->
                val info = s.providers.firstOrNull { it.id == id }
                ChoiceRow(Lucide.Brain, info?.name ?: s.catalog.providers.firstOrNull { it.id == id }?.name ?: id,
                    providerStatus(info).orEmpty(), id == s.provider) { onProvider(id) }
            }
        }
    }
    RowGroup {
        ActionRow("Refresh models and sign-in", Lucide.RefreshCw, onRefresh, enabled = !s.busy)
    }
    if (onEndpoint != null) {
        var adding by remember { mutableStateOf(false) }
        RowGroup { ActionRow("Add OpenAI-compatible endpoint", Lucide.Plus, { adding = !adding }) }
        if (adding) EndpointForm { id, model -> adding = false; onEndpoint(id, model) }
    }
    RowGroup {
        if (!group?.chatModels.isNullOrEmpty()) PickerRow("Model", (group.chatModels.map { it.id } + s.model).filter(String::isNotBlank).distinct(), s.model,
            { id -> group.chatModels.firstOrNull { it.id == id }?.name ?: id }, { value -> onEdit { it.copy(model = value) } }, Lucide.Cpu)
        else key(s.provider) { DraftRow("Model ID", s.model, "Enter a model ID") { value -> onEdit { it.copy(model = value) } } }
        account?.downloads?.let { downloads ->
            downloads.models.filter { it.id == s.model }.takeIf { it.isNotEmpty() }?.let { selected ->
                ModelDownloadRows(downloads.copy(models = selected), { onDownload() }, onCancel, {})
            }
        }
        account?.apiKey?.let { key ->
            key(s.provider) { DraftRow(if (key.configured) "Replace API key" else "API key", s.apiKey, "API key or secret reference", secret = true) { value -> onEdit { it.copy(apiKey = value) } } }
        }
        s.deviceCode?.let { code ->
            val uri = LocalUriHandler.current
            GroupBox {
                Text("Enter this code on the sign-in page", style = TextStyles.secondary, color = color(mutedColor))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                    Text(code.code, style = MonoStyle, fontSize = TextStyles.headline.fontSize, fontWeight = FontWeight.Bold)
                    CopyButton(code.code)
                }
            }
            ActionRow("Open sign-in page", Lucide.ExternalLink, { runCatching { uri.openUri(code.url) } })
        }
    }
    (account?.downloads?.error ?: s.catalog.model(s.provider, s.model)?.state?.reason ?: group?.warning ?: s.catalog.warning ?: account?.error)?.let { Text(it, color = color(mutedColor), style = TextStyles.caption) }
}

private const val APPLE = "apple"

private fun providerStatus(info: ProviderInfo?): String? = when {
    info?.account?.signedIn == true -> "Signed in" + info.account?.label?.let { " as $it" }.orEmpty()
    info?.account != null -> "Sign in to continue"
    info?.apiKey?.configured == true -> "API key saved"
    info?.apiKey != null -> "Needs an API key"
    info?.downloads != null -> "Private · runs on this Server, downloads a model"
    else -> null
}

internal fun voicePreview(s: OnboardingState): String = buildString {
    append(if (s.choices[1] == 0) "Hey" else "Hello")
    s.userName.takeIf { it.isNotBlank() }?.let { append(" $it") }
    append(if (s.choices[3] == 0) "! " else ". ")
    append(if (s.choices[0] == 0) "I'm here to help you think this through. " else "Let's turn this into a clear next step. ")
    append(if (s.choices[2] == 0) "I'll put together a starting point." else "Where would you like to start?")
    if (s.choices[4] == 1) append(" And I'll tell you what I think, straight up.")
}
