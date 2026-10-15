package kinuvo.ui.agent

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.session.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.animation.AnimatedContent
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

private val Phases = listOf(Lucide.House, Lucide.Brain, Lucide.User, Lucide.Smile, Lucide.Sparkles, Lucide.Plug)
private val PhaseNames = listOf("Home", "Model", "You", "Identity", "Soul", "Connect")
private val Headlines = listOf("Where should I live?", "Which mind should I use?", "What should I call you?", "", "How should I show up?", "What can I work with?")
private val Subtitles = listOf(
    "Pick where your Server runs. You can add more Servers later.",
    "Your model powers every conversation. Each provider decides where your messages are processed.",
    "Optional. I'll keep it in my notes about you.",
    "Swipe or tap to change how I look, then give me a name.",
    "Five choices shape my Soul. You can change it anytime in Settings.",
    "Optional. Each one lets me do more for you; all of them are in Settings later.",
)
private val Pitches = listOf("Remembers what matters to you.", "Checks in while you sleep.", "Researches the web for you.", "Lives on your own Server.")
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
        onboarding.load(a.agents.firstOrNull().takeIf { ready != null && attached })
    }
    var local by rememberSaveable { mutableStateOf(a.pendingPair == null && Server.Embedded in a.servers) }
    val url = rememberTextFieldState(a.pendingPair?.server ?: org.koin.compose.koinInject<Device>().serverUrl)
    val code = rememberTextFieldState(a.pendingPair?.code.orEmpty())
    val alias = rememberTextFieldState()
    LaunchedEffect(a.pendingPair) { a.pendingPair?.let {
        url.setTextAndPlaceCursorAtEnd(it.server); code.setTextAndPlaceCursorAtEnd(it.code); local = false
        onboarding.edit { s -> if (s.step == 0) s.copy(step = 1) else s }
    } }
    LaunchedEffect(url) {
        snapshotFlow { url.text.toString() }.collect { text -> Links.parsePair(text)?.let {
            url.setTextAndPlaceCursorAtEnd(it.server); code.setTextAndPlaceCursorAtEnd(it.code)
        } }
    }
    var advancing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.details, advancing, ready?.client) {
        if (advancing && ready != null && s.details != null) { onboarding.edit { it.copy(step = 2) }; advancing = false }
    }
    LaunchedEffect(a.connection, s.error) { if (a.connection is Connection.Unpaired || s.error != null) advancing = false }
    val account = s.providers.firstOrNull { it.id == s.provider }
    val next = { onboarding.edit { it.moved(1) } }
    val action = when {
        s.step == 0 -> OnboardingAction("Get started", run = next)
        s.step == 1 -> OnboardingAction(if (advancing) "Connecting…" else "Continue", !advancing && (local || url.value().isNotBlank() && code.value().isNotBlank())) {
            val selected = if (local) Server.Embedded else ready?.server?.takeIf {
                it is Server.Remote && it.url == runCatching { normalizeUrl(url.value()) }.getOrNull()
            }
            if (selected != null && ready?.server == selected) {
                if (s.details != null) onboarding.edit { it.copy(step = 2) }
                else { advancing = true; onboarding.load(a.agents.firstOrNull()) }
            } else { advancing = true; if (local) app.use(Server.Embedded) else app.pair(url.value(), code.value(), alias.value()) }
        }
        s.step == 2 && account?.apiKey != null && s.apiKey.isNotBlank() -> OnboardingAction("Save key") { onboarding.saveKey(s.apiKey) }
        s.step == 2 && account?.account?.signedIn == false && s.deviceCode == null -> OnboardingAction("Sign in to ${account.name}") { onboarding.login() }
        s.step == 2 && account?.account?.signedIn == false -> OnboardingAction("I've signed in") { onboarding.refresh() }
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
            secondary = OnboardingAction("I already have a Server") { local = false; next() },
            connectionError = (a.connection as? Connection.Unpaired)?.error, home = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OnboardingPlace(Lucide.Laptop, "This device", "Your Agent's data stays right here.", local, { local = true }, Modifier.weight(1f).fillMaxHeight(), Server.Embedded in a.servers)
                    OnboardingPlace(Lucide.Cloud, "Remote server", "Take your Agents to every device.", !local, { local = false }, Modifier.weight(1f).fillMaxHeight())
                }
                if (Server.Embedded !in a.servers) Text("Local hosting is available in the desktop and Android apps.", color = color(mutedColor), fontSize = 13.sp)
                AnimatedVisibility(!local, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        a.servers.filterIsInstance<Server.Remote>().forEach { server ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color(subtleColor)).clickable {
                                if (server.token != null) { advancing = true; app.use(server) }
                                else { url.setTextAndPlaceCursorAtEnd(server.url); alias.setTextAndPlaceCursorAtEnd(server.name.orEmpty()) }
                            }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Lucide.Server, Modifier.size(16.dp))
                                Text(server.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Icon(Lucide.ChevronRight, Modifier.size(16.dp), tint = color(mutedColor))
                            }
                        }
                        OnboardingField("Server address or pairing link") { Input(url, "https://your-server:8787") }
                        OnboardingField("Pairing code") { Input(code, "Code from the Server") }
                        OnboardingField("Name on this device (optional)") { Input(alias, "Home server") }
                        Text("Run kinuvo pair for chat access, or kinuvo pair --manage to configure the Server.", color = color(mutedColor), fontSize = 13.sp)
                    }
                }
            }
        }, model = { ModelStep(s, onboarding::selectProvider, onboarding::refresh, onboarding::edit) },
            connect = { ConnectList(s, a.capabilities::allows, onboarding::setUp) })
        LaunchedEffect(s.step == ConnectStep) { if (s.step == ConnectStep) onboarding.loadConnected() }
        val setup = s.setup
        val agentId = s.details?.info?.id
        if (setup != null && agentId != null) ConnectSetup(app, agentId, setup) { onboarding.setUp(null) }
    }
}

/** The Settings section for [section] over the onboarding, without the other sections. */
@Composable
private fun ConnectSetup(app: AppViewModel, agentId: String, section: kinuvo.app.navigation.SettingsSection, onDone: () -> Unit) {
    var route by remember(section) { mutableStateOf(kinuvo.app.navigation.Route.Settings(agentId, section)) }
    // The overlay owns its ViewModels: each route's is cleared when the route changes or the overlay closes.
    val owner = remember(route) { object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = androidx.lifecycle.ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val settings = org.koin.compose.viewmodel.koinViewModel<SettingsViewModel>(viewModelStoreOwner = owner) { org.koin.core.parameter.parametersOf(route) }
    val back = { if (route.item != null || route.editing) route = route.copy(item = null, editing = false) else onDone() }
    androidx.navigationevent.compose.NavigationBackHandler(androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None)) { back() }
    Box(Modifier.fillMaxSize().background(color(backgroundColor)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        SettingsScreen(app, settings, onNavigate = { r, _ -> if (r.section == section) route = r else onDone() },
            onBack = back, onOpenSession = {}, sidebar = false)
    }
}

private val ConnectInfo = mapOf(
    kinuvo.app.navigation.SettingsSection.Secrets to Triple(Lucide.KeyRound, "Password manager", "Use passwords and API keys without pasting them into settings."),
    kinuvo.app.navigation.SettingsSection.Knowledge to Triple(Lucide.Library, "Notes", "Your Obsidian vault or Anytype space, so I know your people and projects."),
    kinuvo.app.navigation.SettingsSection.Tasks to Triple(Lucide.ListChecks, "Tasks", "Your reminders, so I can see and work on your to-dos."),
    kinuvo.app.navigation.SettingsSection.Notifications to Triple(Lucide.Bell, "Notifications", "So I can reach you when something needs you."),
    kinuvo.app.navigation.SettingsSection.Search to Triple(Lucide.Search, "Web search", "Search the web, not just open pages."),
    kinuvo.app.navigation.SettingsSection.Workspace to Triple(Lucide.Terminal, "Shell and files", "This machine, a container or an SSH host where I can run commands."),
    kinuvo.app.navigation.SettingsSection.Mcp to Triple(Lucide.Plug, "Apps and services", "Notion, Linear, GitHub, Google and more."),
)

@Composable
internal fun ConnectList(s: OnboardingState, available: (kinuvo.app.navigation.SettingsSection) -> Boolean, onSetUp: (kinuvo.app.navigation.SettingsSection) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectSections.forEach { section ->
            val (icon, title, description) = ConnectInfo.getValue(section)
            val done = s.connected[section] == true
            val enabled = available(section)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color(subtleColor)).clickable(enabled, role = Role.Button) { onSetUp(section) }
                .alpha(if (enabled) 1f else 0.5f).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(description, color = color(mutedColor), fontSize = 13.sp)
                }
                when {
                    !enabled -> Text("Not on this Server", color = color(mutedColor), fontSize = 13.sp)
                    done -> Pill("On", tone = Tone.Success, icon = Lucide.CircleCheck)
                    else -> Text("Set up", color = color(mutedColor), fontSize = 13.sp)
                }
                if (enabled) Icon(Lucide.ChevronRight, Modifier.size(16.dp), tint = color(mutedColor))
            }
        }
    }
}

/** The onboarding's Identity and Soul steps, creating a new Agent with the shown Agent's model. */
@Composable
internal fun NewAgentScreen(app: AppViewModel, onboarding: OnboardingViewModel, onClose: () -> Unit, onCreated: (String) -> Unit) {
    val a by app.state.collectAsState()
    val s by onboarding.state.collectAsState()
    val from = a.agents.firstOrNull { it.id == a.agentId } ?: a.agents.firstOrNull()
    LaunchedEffect(from?.id) { if (from != null && s.details == null) onboarding.newAgent(from, a.agents) }
    val action = if (s.step == SoulStep) OnboardingAction("Meet ${s.agentName.ifBlank { "your Agent" }}", s.details != null) {
        onboarding.create(a.agents.map { it.id }, onCreated)
    } else OnboardingAction("Continue", s.agentName.isNotBlank()) { onboarding.edit { it.moved(1) } }
    OnboardingContent(s, onEdit = onboarding::edit, onChoice = onboarding::choose, action = action, onClose = onClose)
}

@Composable
internal fun OnboardingContent(
    s: OnboardingState,
    onEdit: ((OnboardingState) -> OnboardingState) -> Unit = {},
    onChoice: (Int, Int) -> Unit = { _, _ -> },
    action: OnboardingAction = OnboardingAction("Continue"),
    secondary: OnboardingAction? = null,
    onClose: (() -> Unit)? = null,
    working: Boolean = false,
    connectionError: String? = null,
    home: @Composable () -> Unit = {},
    model: @Composable () -> Unit = {},
    connect: @Composable () -> Unit = {},
    insets: WindowInsets = WindowInsets.safeDrawing,
) {
    val back = when {
        s.busy -> null
        s.step != s.steps.first() -> { { onEdit { it.moved(-1) } } }
        else -> onClose
    }
    androidx.navigationevent.compose.NavigationBackHandler(androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None),
        isBackEnabled = back != null) { back?.invoke() }
    var featured by remember { mutableIntStateOf(0) }
    val pokes = remember { mutableStateListOf(0, 0, 0, 0) }
    val feature = { i: Int -> featured = i; pokes[i] += 1 }
    if (s.step == 0) LaunchedEffect(featured) { delay(2800); feature((featured + 1) % AvatarArtboards.size) }
    val tint by animateColorAsState(AvatarInk[if (s.step == 0) featured else s.avatar], tween(700))
    CompositionLocalProvider(LocalTint provides tint) {
        Box(Modifier.fillMaxSize().background(color(backgroundColor)).drawBehind {
            val wide = size.width >= 800.dp.toPx()
            val center = if (wide) Offset(size.width * 0.27f, size.height * 0.5f) else Offset(size.width / 2, size.height * if (s.step == 0) 0.32f else 0.2f)
            drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.34f), tint.copy(alpha = 0f)), center, size.minDimension * if (wide) 0.6f else 0.75f))
        }) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(insets).imePadding()) {
                val wide = maxWidth >= 800.dp
                val roomy = maxHeight >= 600.dp
                val gutter = if (wide) 48.dp else 22.dp
                Column(Modifier.fillMaxSize().padding(horizontal = gutter, vertical = 12.dp)) {
                    TopBar(s, onEdit, onClose, gutter)
                    if (s.step == 0) {
                        if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1.1f).fillMaxHeight(), contentAlignment = Alignment.Center) { PrimerStage(featured, pokes, feature, Modifier.fillMaxWidth().aspectRatio(1f)) }
                            Column(Modifier.weight(1f)) {
                                PrimerCopy(featured, true)
                                Spacer(Modifier.height(36.dp))
                                Cta(action, working, Modifier.widthIn(min = 260.dp))
                                secondary?.let { TextAction(it, Modifier.widthIn(min = 260.dp)) }
                            }
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) { PrimerStage(featured, pokes, feature, Modifier.fillMaxWidth().aspectRatio(1f)) }
                            PrimerCopy(featured, false)
                            Spacer(Modifier.height(28.dp))
                            Cta(action, working, Modifier.fillMaxWidth())
                            secondary?.let { TextAction(it, Modifier.fillMaxWidth()) }
                        }
                    } else {
                        val form: @Composable ColumnScope.() -> Unit = {
                            StepForm(s, onEdit, onChoice, wide, home, model, connect, Modifier.weight(1f).fillMaxWidth())
                            (s.error ?: connectionError)?.let { Text(it, color = color(destructiveColor), modifier = Modifier.padding(bottom = 10.dp)) }
                            Cta(action, s.busy || working, if (wide) Modifier.widthIn(min = 260.dp) else Modifier.fillMaxWidth())
                        }
                        if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
                            AgentStage(s, onEdit, true, Modifier.weight(1.1f).fillMaxHeight())
                            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 24.dp), content = form)
                        } else {
                            if (roomy) AgentStage(s, onEdit, false, Modifier.fillMaxWidth().height(if (s.step == 4) 230.dp else 190.dp))
                            Column(Modifier.weight(1f), content = form)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit, onClose: (() -> Unit)?, gutter: Dp) {
    val chrome = LocalWindowChrome.current
    // Clear of the desktop window buttons the content draws under, on both sides so the track stays centred.
    val inset = (maxOf(chrome?.leadingInset ?: 0.dp, chrome?.trailingInset ?: 0.dp) - gutter).coerceAtLeast(0.dp)
    Row(Modifier.fillMaxWidth().height(64.dp).padding(top = 4.dp).padding(horizontal = inset)) {
        Box(Modifier.width(48.dp).height(38.dp), contentAlignment = Alignment.CenterStart) {
            if (s.step != s.steps.first()) HeaderButton(Lucide.ArrowLeft, "Back", { onEdit { it.moved(-1) } }, enabled = !s.busy)
            else onClose?.let { HeaderButton(Lucide.X, "Cancel", it, enabled = !s.busy) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (s.step == 0) Text("kinuvo", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            else PhaseTrack(s.steps.filter { it > 0 }, s.step) { step -> if (!s.busy) onEdit { it.copy(step = step, error = null) } }
        }
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun PhaseTrack(phases: List<Int>, step: Int, onJump: (Int) -> Unit) {
    val tint = LocalTint.current
    val ink = color(onBackgroundColor)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            phases.forEachIndexed { index, phase ->
                val icon = Phases[phase - 1]
                val current = phase == step
                val done = phase < step
                if (index > 0) {
                    val line by animateColorAsState(if (phase <= step) tint else color(borderColor))
                    Box(Modifier.width(12.dp).height(2.dp).background(line))
                }
                val background by animateColorAsState(when { current -> ink; done -> tint.copy(alpha = 0.35f); else -> Color.Transparent })
                val size by animateDpAsState(if (current) 38.dp else 30.dp, spring(dampingRatio = 0.6f))
                Box(Modifier.size(size).clip(CircleShape).background(background)
                    .then(if (!current && !done) Modifier.border(1.dp, color(borderColor), CircleShape) else Modifier)
                    .then(if (done) Modifier.titleBarControl().clickable(role = Role.Button) { onJump(phase) } else Modifier)
                    .semantics { contentDescription = PhaseNames[phase - 1] }, contentAlignment = Alignment.Center) {
                    Icon(icon, Modifier.size(if (current) 18.dp else 14.dp), tint = when { current -> color(backgroundColor); done -> ink; else -> color(mutedColor) })
                }
            }
        }
        AnimatedContent(PhaseNames[step - 1], transitionSpec = { fadeIn() togetherWith fadeOut() }) { Text(it, fontSize = 12.sp, color = color(mutedColor)) }
    }
}

@Composable
private fun PrimerStage(featured: Int, pokes: List<Int>, onTap: (Int) -> Unit, modifier: Modifier) {
    val spots = listOf(Triple(0.32f, 0.34f, 0.46f), Triple(0.76f, 0.24f, 0.34f), Triple(0.26f, 0.76f, 0.32f), Triple(0.7f, 0.7f, 0.42f))
    BoxWithConstraints(modifier) {
        val unit = minOf(maxWidth, maxHeight)
        spots.forEachIndexed { index, (x, y, scale) ->
            val (fx, fy) = spots[featured].let { it.first to it.second }
            val gaze = when {
                index == featured -> Gaze.Forward
                abs(fx - x) > abs(fy - y) -> if (fx > x) Gaze.Right else Gaze.Left
                else -> if (fy > y) Gaze.Down else Gaze.Up
            }
            val emphasis by animateFloatAsState(if (index == featured) 1.12f else 0.9f, spring(dampingRatio = 0.5f, stiffness = 200f))
            val size = unit * scale
            Floating(AvatarArtboards[index], size, gaze, pokes[index], index * 1.7f, Modifier
                .offset(maxWidth * x - size / 2, maxHeight * y - size / 2)
                .graphicsLayer { scaleX = emphasis; scaleY = emphasis }
                .pointerInput(index) { detectTapGestures { onTap(index) } })
        }
    }
}

@Composable
private fun PrimerCopy(featured: Int, wide: Boolean) {
    val display = displayStyle(if (wide) 56.sp else 38.sp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.heightIn(min = with(androidx.compose.ui.platform.LocalDensity.current) { (display.lineHeight * 2).toDp() }), contentAlignment = Alignment.BottomStart) {
            AnimatedContent(featured, transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 260f)) { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            }) { Text(Pitches[it], style = display) }
        }
        Text("Kinuvo is a personal AI assistant you host yourself. Setup takes about a minute.", color = color(mutedColor), fontSize = if (wide) 18.sp else 16.sp)
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
            Floating(AvatarArtboards[avatar], faceSize, gaze, poke, 0f, Modifier.pointerInput(Unit) { detectTapGestures { poke++ } }.semantics { contentDescription = AvatarArtboards[avatar] })
        }
    }
    if (s.step == SoulStep && !wide) Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        face(110.dp)
        SpeechBubble(voicePreview(s), Modifier.weight(1f), RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (s.step == 4) {
            var drag by remember { mutableFloatStateOf(0f) }
            Row(Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { if (abs(drag) > 48.dp.toPx()) cycle(if (drag < 0) 1 else -1); drag = 0f }) { _, delta -> drag += delta }
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 0.dp)) {
                listOf(-1, 0, 1).forEach { delta ->
                    if (delta == 0) face(size)
                    else Floating(AvatarArtboards[(s.avatar + delta + count) % count], size * 0.42f, if (delta < 0) Gaze.Right else Gaze.Left, 0, delta.toFloat(),
                        Modifier.alpha(0.5f).clip(CircleShape).clickable(role = Role.Button) { cycle(delta) }.semantics { contentDescription = if (delta < 0) "Previous look" else "Next look" })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarInk.forEachIndexed { index, ink ->
                    val width by animateDpAsState(if (index == s.avatar) 24.dp else 8.dp, spring(dampingRatio = 0.6f))
                    Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { onEdit { it.copy(avatar = index) } }.padding(5.dp).semantics { contentDescription = AvatarArtboards[index] }) {
                        Box(Modifier.size(width, 8.dp).clip(CircleShape).background(ink))
                    }
                }
            }
        } else face(size)
        if (s.step == SoulStep) {
            Spacer(Modifier.height(20.dp))
            SpeechBubble(voicePreview(s), Modifier.widthIn(max = 380.dp), RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp))
        }
    }
}

@Composable
private fun Floating(artboard: String, size: Dp, gaze: Gaze, poke: Int, phase: Float, modifier: Modifier = Modifier) {
    val bob by rememberInfiniteTransition().animateFloat(0f, 2 * PI.toFloat(), infiniteRepeatable(tween(3800, easing = LinearEasing)))
    val pop = remember { Animatable(1f) }
    LaunchedEffect(poke) { if (poke > 0) { pop.animateTo(1.06f, tween(110)); pop.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 320f)) } }
    RiveAvatar(BundledAvatar, modifier.size(size).graphicsLayer {
        translationY = sin(bob + phase) * 5.dp.toPx(); scaleX = pop.value; scaleY = pop.value
    }, gaze, artboard, poke)
}

@Composable
private fun SpeechBubble(text: String, modifier: Modifier, shape: RoundedCornerShape) {
    Box(modifier.clip(shape).background(color(panelColor)).border(1.dp, color(borderColor), shape).padding(horizontal = 16.dp, vertical = 12.dp)) {
        AnimatedContent(text, transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(120)) }) { Text(it, fontSize = 15.sp, lineHeight = 21.sp) }
    }
}

@Composable
private fun StepForm(
    s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit, onChoice: (Int, Int) -> Unit, wide: Boolean,
    home: @Composable () -> Unit, model: @Composable () -> Unit, connect: @Composable () -> Unit, modifier: Modifier,
) {
    AnimatedContent(s.step, transitionSpec = {
        val forward = targetState > initialState
        (fadeIn() + slideInHorizontally { if (forward) it / 6 else -it / 6 }) togetherWith (fadeOut() + slideOutHorizontally { if (forward) -it / 6 else it / 6 })
    }, modifier = modifier) { step ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val display = displayStyle(if (wide) 40.sp else 30.sp)
            if (step == 4) NameHeadline(s, display, onEdit) else Text(Headlines[step - 1], style = display)
            Text(Subtitles[step - 1], color = color(mutedColor))
            Spacer(Modifier.height(2.dp))
            when (step) {
                1 -> home()
                2 -> model()
                3 -> {
                    DraftField("Your name", s.userName, "Alex") { value -> onEdit { it.copy(userName = value) } }
                    DraftField("What you do", s.occupation, "Product designer, student, retired…") { value -> onEdit { it.copy(occupation = value) } }
                }
                ConnectStep -> connect()
                SoulStep -> {
                    PersonalityChoices.indices.forEach { index -> TraitSwitch(index, s.choices[index]) { onChoice(index, it) } }
                    PromptEditor(s, onEdit)
                }
            }
        }
    }
}

@Composable
private fun NameHeadline(s: OnboardingState, display: TextStyle, onEdit: ((OnboardingState) -> OnboardingState) -> Unit) {
    val tint = LocalTint.current
    val field = rememberTextFieldState(s.agentName)
    LaunchedEffect(s.agentName) { if (field.text.toString() != s.agentName) field.setTextAndPlaceCursorAtEnd(s.agentName) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { value -> onEdit { it.copy(agentName = value) } } }
    Column {
        Text("Hey ${s.userName.ifBlank { "there" }}, I'm", style = display)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(field, Modifier.weight(1f).drawBehind {
                drawRect(tint, Offset(0f, size.height - 3.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
            }.semantics { contentDescription = "Agent name" }, textStyle = display.copy(color = color(onBackgroundColor)), cursorBrush = SolidColor(tint),
                lineLimits = TextFieldLineLimits.SingleLine, keyboardOptions = inputKeyboard(true))
            HeaderButton(Lucide.Dices, "Suggest a name", { onEdit { it.copy(agentName = NameIdeas.filter { name -> name != it.agentName }.random()) } })
        }
    }
}

@Composable
private fun TraitSwitch(index: Int, selected: Int, onSelect: (Int) -> Unit) {
    val tint = LocalTint.current
    val thumb by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.7f, stiffness = 500f))
    BoxWithConstraints(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(color(subtleColor))) {
        Box(Modifier.offset(x = maxWidth / 2 * thumb).width(maxWidth / 2).fillMaxHeight().padding(4.dp).clip(CircleShape).background(tint.copy(alpha = 0.4f)))
        Row(Modifier.fillMaxSize()) {
            PersonalityChoices[index].second.forEachIndexed { choice, option ->
                val on = choice == selected
                val ink by animateColorAsState(color(if (on) onBackgroundColor else mutedColor))
                Row(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable(role = Role.RadioButton) { onSelect(choice) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (choice == 0) TraitIcons[index].first else TraitIcons[index].second, Modifier.size(16.dp), tint = ink)
                    Spacer(Modifier.width(8.dp))
                    Text(option, color = ink, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun PromptEditor(s: OnboardingState, onEdit: ((OnboardingState) -> OnboardingState) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Lucide.Pencil, Modifier.size(15.dp), tint = color(mutedColor))
        Text("Edit the full prompt", color = color(mutedColor), fontSize = 14.sp)
        Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, Modifier.size(14.dp), tint = color(mutedColor))
    }
    AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val prompt = rememberTextFieldState(s.prompt)
        LaunchedEffect(s.prompt) { if (prompt.text.toString() != s.prompt) prompt.setTextAndPlaceCursorAtEnd(s.prompt) }
        LaunchedEffect(prompt) { snapshotFlow { prompt.text.toString() }.collect { value -> onEdit { it.copy(prompt = value) } } }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextArea(prompt, lines = 8)
            Text("Changing a choice above rebuilds this prompt.", color = color(mutedColor), fontSize = 12.sp)
        }
    }
}

@Composable
internal fun Cta(action: OnboardingAction, busy: Boolean, modifier: Modifier) {
    val enabled = action.enabled && !busy
    val ink by animateColorAsState(color(onBackgroundColor).copy(alpha = if (enabled) 1f else 0.3f))
    Row(modifier.height(56.dp).clip(CircleShape).background(ink).clickable(enabled, role = Role.Button, onClick = action.run).padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (busy) { Spinner(tint = color(backgroundColor)); Spacer(Modifier.width(10.dp)) }
        AnimatedContent(action.label, transitionSpec = { fadeIn() togetherWith fadeOut() }) {
            Text(it, color = color(backgroundColor), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
internal fun TextAction(action: OnboardingAction, modifier: Modifier) {
    Box(modifier.height(48.dp).clip(CircleShape).clickable(action.enabled, role = Role.Button, onClick = action.run), contentAlignment = Alignment.Center) {
        Text(action.label, color = color(mutedColor), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
internal fun OnboardingPlace(icon: ImageVector, title: String, description: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tint = LocalTint.current
    val shape = RoundedCornerShape(22.dp)
    val border by animateColorAsState(if (selected) tint else color(borderColor))
    val badge by animateColorAsState(if (selected) tint.copy(alpha = 0.35f) else color(subtleColor))
    Column(modifier.alpha(if (enabled) 1f else 0.45f).clip(shape).background(color(panelColor)).border(if (selected) 2.dp else 1.dp, border, shape)
        .clickable(enabled, role = Role.RadioButton, onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) { Icon(icon, Modifier.size(22.dp)) }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) { Icon(Lucide.CircleCheck, Modifier.size(20.dp)) }
        }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(description, color = color(mutedColor), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun OnboardingField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); content() }
}

@Composable
private fun DraftField(label: String, value: String, placeholder: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val field = rememberTextFieldState(value)
    LaunchedEffect(value) { if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(onChange) }
    OnboardingField(label) { Input(field, placeholder, secret = secret, capitalize = !secret) }
}

@Composable
internal fun ModelStep(s: OnboardingState, onProvider: (String) -> Unit, onRefresh: () -> Unit, onEdit: ((OnboardingState) -> OnboardingState) -> Unit) {
    val tint = LocalTint.current
    val providers = (s.catalog.providers.map { it.id } + s.providers.map { it.id } + s.provider.takeIf { it.isNotBlank() }.let { listOfNotNull(it) }).distinct()
    val group = s.catalog.providers.firstOrNull { it.id == s.provider }
    val account = s.providers.firstOrNull { it.id == s.provider }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Provider", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        HeaderButton(Lucide.RefreshCw, "Refresh models and sign-in", onRefresh, enabled = !s.busy)
    }
    providers.forEach { id ->
        val info = s.providers.firstOrNull { it.id == id }
        val selected = id == s.provider
        val shape = RoundedCornerShape(18.dp)
        val border by animateColorAsState(if (selected) tint else color(borderColor))
        Row(Modifier.fillMaxWidth().clip(shape).background(color(panelColor)).border(if (selected) 2.dp else 1.dp, border, shape)
            .clickable(role = Role.RadioButton) { onProvider(id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (selected) tint.copy(alpha = 0.35f) else color(subtleColor)), contentAlignment = Alignment.Center) {
                Icon(if (selected) Lucide.Check else Lucide.Brain, Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(info?.name ?: s.catalog.providers.firstOrNull { it.id == id }?.name ?: id, fontWeight = FontWeight.Bold)
                providerStatus(info)?.let { Text(it, color = color(mutedColor), fontSize = 13.sp) }
            }
        }
    }
    if (!group?.models.isNullOrEmpty()) OnboardingField("Model") {
        Picker((group.models.map { it.id } + s.model).filter(String::isNotBlank).distinct(), s.model, { id -> group.models.firstOrNull { it.id == id }?.name ?: id }, { value -> onEdit { it.copy(model = value) } })
    } else key(s.provider) { DraftField("Model ID", s.model, "Enter a model ID") { value -> onEdit { it.copy(model = value) } } }
    account?.apiKey?.let { key ->
        key(s.provider) { DraftField(if (key.configured) "Replace API key" else "API key", s.apiKey, "API key or secret reference", secret = true) { value -> onEdit { it.copy(apiKey = value) } } }
    }
    s.deviceCode?.let { code ->
        val uri = LocalUriHandler.current
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha = 0.16f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enter this code on the sign-in page", fontSize = 13.sp, color = color(mutedColor))
            Text(code.code, style = MonoStyle, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { runCatching { uri.openUri(code.url) } }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Open sign-in page", fontWeight = FontWeight.SemiBold)
                Icon(Lucide.ExternalLink, Modifier.size(14.dp))
            }
        }
    }
    (s.catalog.model(s.provider, s.model)?.state?.reason ?: group?.warning ?: s.catalog.warning ?: account?.error)?.let { Text(it, color = color(mutedColor), fontSize = 12.sp) }
}

private fun providerStatus(info: ProviderInfo?): String? = when {
    info?.account?.signedIn == true -> "Signed in" + info.account?.label?.let { " as $it" }.orEmpty()
    info?.account != null -> "Sign in to continue"
    info?.apiKey?.configured == true -> "API key saved"
    info?.apiKey != null -> "Needs an API key"
    else -> null
}

@Composable
internal fun displayStyle(size: androidx.compose.ui.unit.TextUnit) =
    LocalTextStyle.current.copy(fontSize = size, lineHeight = size * 1.1f, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)

internal fun voicePreview(s: OnboardingState): String = buildString {
    append(if (s.choices[1] == 0) "Hey" else "Hello")
    s.userName.takeIf { it.isNotBlank() }?.let { append(" $it") }
    append(if (s.choices[3] == 0) "! " else ". ")
    append(if (s.choices[0] == 0) "I'm here to help you think this through. " else "Let's turn this into a clear next step. ")
    append(if (s.choices[2] == 0) "I'll put together a starting point." else "Where would you like to start?")
    if (s.choices[4] == 1) append(" And I'll tell you what I think, straight up.")
}
