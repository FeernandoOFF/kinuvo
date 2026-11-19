package kinuvo.ui.navigation

import kinuvo.app.agent.external
import kinuvo.protocol.team.Team
import kinuvo.protocol.team.TeamId
import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.navigation.*
import kinuvo.ui.connection.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.settings.*
import kinuvo.ui.board.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.routine.Routine
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.capability.supports
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionStatus
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.ui.design.*
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * The app shell: the rail of Home, owners and the Server, and the column for what the rail selected: Home's
 * destinations, or the shown [agent]'s Sessions. [view] is the screen open beside it, to highlight. With [home], the
 * column lists Home. On Settings the rail stands alone, beside Settings' own sections.
 */
data class ShellState(
    val agents: List<AgentInfo> = emptyList(),
    val agent: AgentInfo? = null,
    val lists: Map<AgentId, SessionList> = emptyMap(),
    val finished: Set<SessionId> = emptySet(),
    val view: Route? = null,
    val servers: ServerMenuState = ServerMenuState(),
    val capabilities: ServerCapabilities? = null,
    val routines: Map<AgentId, List<Routine>> = emptyMap(),
    /** Whether this Device has management access, which watching and taking over Computers needs. */
    val management: Boolean = true,
    val home: Boolean = false,
    val teams: List<Team> = emptyList(),
    /** Sessions the user settled on this Device, which leave the inbox until there's news. */
    val settled: Set<SessionId> = emptySet(),
) {
    val sessions get() = agent?.let { lists[it.id] }?.sessions.orEmpty()
    val hasMore get() = agent?.let { lists[it.id] }?.hasMore == true
    /** Routines that continue one Session, listed under Routines so it stays at hand. */
    val attached get() = agent?.let { routines[it.id] }.orEmpty().filter { it.sessionId != null }
    private val unattached get() = attached.mapNotNull { it.sessionId }.toSet().let { ids -> sessions.filter { it.id !in ids } }
    val favorites get() = unattached.filter { it.favorite }
    val chats get() = unattached.filter { !it.favorite }
    fun isSettled(session: SessionInfo) = session.id in settled && session.status == SessionStatus.Idle
    /** The inbox: Sessions still needing a look, and the [settledChats] set aside. */
    val inbox get() = chats.filter { !isSettled(it) }
    val settledChats get() = chats.filter(::isSettled)
    val settings get() = view is Route.Settings && !view.home
    private val everySession get() = lists.values.flatMap { it.sessions }.distinctBy { it.id }
    /** Sessions across Agents waiting on an Approval or an answer, and those running, for Home's column. */
    val waiting get() = everySession.filter { it.status == SessionStatus.Waiting }
    val running get() = everySession.filter { it.status == SessionStatus.Running }

    /** An Agent has news when one of its Sessions runs, waits on the user or finished while elsewhere. */
    fun hasNews(agentId: AgentId) = lists[agentId]?.sessions.orEmpty().any { it.status != SessionStatus.Idle || (it.id in finished && it.id !in settled) }

    fun avatarState(agentId: AgentId) = kinuvo.app.agent.avatarState(lists[agentId]?.sessions.orEmpty().map { it.status })
}

class ShellActions(
    /** Opens a Team, or the form for a new one when null. */
    val onTeam: (TeamId?) -> Unit = {},
    val onSelectAgent: (AgentId) -> Unit = {},
    val onAddAgent: () -> Unit = {},
    val onNew: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    /** Settles a Session out of the inbox, or puts it back when false. */
    val onSettle: (SessionId, Boolean) -> Unit = { _, _ -> },
    val onSettings: () -> Unit = {},
    /** Opens the shown Agent's settings beside the open screen. */
    val onAgentSettings: () -> Unit = {},
    /** Selects Home in the rail. */
    val onHome: () -> Unit = {},
    val onToday: () -> Unit = {},
    val onBoard: () -> Unit = {},
    val onKnowledge: () -> Unit = {},
    val onArtifacts: () -> Unit = {},
    val onComputers: () -> Unit = {},
    val onRoutines: () -> Unit = {},
    val onWorkflows: () -> Unit = {},
    val onTools: () -> Unit = {},
    /** Opens one Agent's Session from Home's column. */
    val onOpenSession: (agentId: AgentId, sessionId: SessionId) -> Unit = { _, _ -> },
    val onSelectServer: (Server) -> Unit = {},
    val onAddServer: () -> Unit = {},
    /** Opens a saved Server's settings. */
    val onServerSettings: (Server.Remote) -> Unit = {},
    val onRenameServer: (Server.Remote, String) -> Unit = { _, _ -> },
    val onForgetServer: (Server.Remote) -> Unit = {},
    /** Starts a Session with the shown Agent by sending the text, opening it when asked; false when it couldn't. */
    val onQuickSend: suspend (agentId: AgentId, text: String, open: Boolean) -> Boolean = { _, _, _ -> false },
)

/**
 * Shell layout state that outlives screens: whether it's [open] beside them on wide layouts or [revealed] from under them
 * on phones, whether phones show the [quickChat] and its [draft], which [ShellSection]s are [collapsed], remembered in
 * [prefs] across launches, whether [allChats] are listed, the Session search [query] and which Routines show their
 * [expanded] earlier runs.
 */
@Stable
class ShellChrome(open: MutableState<Boolean>, private val prefs: Preferences? = null) {
    var open by open
    var revealed by mutableStateOf(false)
    var quickChat by mutableStateOf(false)
    val draft = TextFieldState()
    val query = TextFieldState()
    var expanded by mutableStateOf(emptySet<String>())
    private var collapsedGroups by mutableStateOf(
        prefs?.get(COLLAPSED)?.split(',')?.mapNotNull { name -> ShellSection.entries.firstOrNull { it.name == name } }?.toSet().orEmpty())
    var collapsed: Set<ShellSection>
        get() = collapsedGroups
        set(value) {
            collapsedGroups = value
            prefs?.set(COLLAPSED, value.joinToString(",") { it.name })
        }
    /** Every open Session, loading more on scroll, rather than the few most recent. */
    var allChats by mutableStateOf(false)
    /** Whether the Settled section at the end of the column is unfolded. */
    var showSettled by mutableStateOf(false)

    private companion object {
        const val COLLAPSED = "shell.collapsed"
    }
}

@Composable
fun rememberShellChrome(): ShellChrome {
    val open = rememberSaveable { mutableStateOf(true) }
    val prefs = koinInject<Preferences>()
    return remember(open, prefs) { ShellChrome(open, prefs) }
}

/**
 * The rail and the column. [fill] lays it out for phones, where it's revealed from under the screens. On Settings, only
 * the rail shows: Settings lists its own sections beside it.
 */
@Composable
fun Shell(state: ShellState, actions: ShellActions, chrome: ShellChrome, modifier: Modifier = Modifier, fill: Boolean = false, column: Boolean = true) {
    if (fill) PhoneShell(state, actions, chrome, modifier)
    else Row(modifier.fillMaxHeight().background(color(railColor))) {
        OwnerRail(state, actions)
        if (!state.settings && column) ColumnPanel(Modifier.width(ColumnWidth)) { ContextColumn(state, actions, chrome) }
    }
}

private val ColumnWidth = 248.dp
internal val RailWidth = 72.dp

/** The column sits in the rail with a rounded corner, Discord-style, instead of a straight seam. */
@Composable
private fun ColumnPanel(modifier: Modifier, content: @Composable () -> Unit) =
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(topStart = Radius.large)).background(color(sidebarColor))) { content() }

@Composable
private fun ContextColumn(state: ShellState, actions: ShellActions, chrome: ShellChrome, bottom: Dp = Spacing.none) =
    if (state.home) HomeColumn(state, actions, bottom) else AgentColumn(state, actions, chrome, bottom)

/** Phones, edge to edge: the same rail and column, with the bar opening [QuickChat] floating over their foot. */
@Composable
private fun PhoneShell(state: ShellState, actions: ShellActions, chrome: ShellChrome, modifier: Modifier) {
    val insets = WindowInsets.safeDrawing
    // Room under the lists for the bar floating over their foot.
    val clear = QuickChatBarHeight + insets.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().background(color(railColor)).windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxSize()) {
            OwnerRail(state, actions, Modifier.windowInsetsPadding(insets.only(WindowInsetsSides.Bottom)), top = Spacing.space8)
            ColumnPanel(Modifier.weight(1f)) { ContextColumn(state, actions, chrome, bottom = clear + 16.dp) }
        }
        val sidebar = color(sidebarColor)
        // The lists fade out under the bar instead of showing through around it.
        Box(Modifier.align(Alignment.BottomStart).padding(start = RailWidth).fillMaxWidth().height(clear + 32.dp)
            .background(Brush.verticalGradient(0f to sidebar.copy(alpha = 0f), 0.5f to sidebar)))
        QuickChatBar(state.agent, chrome.draft, Modifier.align(Alignment.BottomStart).padding(start = RailWidth).windowInsetsPadding(insets.only(WindowInsetsSides.Bottom))) { chrome.quickChat = true }
    }
}

/** Home at the top, the owners scrolling between dividers, and the Server and Settings fixed at the foot. */
@Composable
private fun OwnerRail(state: ShellState, actions: ShellActions, modifier: Modifier = Modifier, top: Dp? = null) {
    Column(
        // A touch wider where the window buttons sit over it, so they clear the column.
        modifier.width(if ((LocalWindowChrome.current?.leadingInset ?: 0.dp) > 0.dp) 80.dp else RailWidth).fillMaxHeight().background(color(railColor))
            .padding(top = top ?: LocalWindowChrome.current?.height ?: Spacing.space12, bottom = Spacing.space12),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.space8),
    ) {
        RailItem("Home", selected = state.home && !state.settings, news = state.waiting.isNotEmpty(), onClick = actions.onHome) {
            Icon(Lucide.House, Modifier.size(20.dp), tint = color(if (state.home && !state.settings) onBackgroundColor else mutedColor))
        }
        HorizontalSeparator(Modifier.width(32.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            state.agents.forEachIndexed { i, agent ->
                val selected = !state.home && !state.settings && agent.id == state.agent?.id
                RailItem(agent.name, selected, news = state.hasNews(agent.id), onClick = { actions.onSelectAgent(agent.id) }, shortcut = if (i < 9) shortcut("${i + 1}", shift = true) else null) {
                    AgentAvatar(agent, 40.dp, state = state.avatarState(agent.id))
                }
            }
            RailItem("New agent", selected = false, news = false, onClick = actions.onAddAgent, enabled = state.agent != null) {
                Icon(Lucide.Plus, Modifier.size(20.dp), tint = color(successColor))
            }
        }
        HorizontalSeparator(Modifier.width(32.dp))
        ServerPicker(state.servers, actions)
        RailItem("Settings", selected = state.settings, news = false, onClick = actions.onSettings, enabled = state.agent != null, shortcut = shortcut(",")) {
            Icon(Lucide.Settings, Modifier.size(20.dp), tint = color(if (state.settings) onBackgroundColor else mutedColor))
        }
    }
}

/** A rail button with a pill on the left edge: tall when [selected], a dot when it has [news]. [name] is read out; the tooltip adds the [shortcut]. */
@Composable
private fun RailItem(name: String, selected: Boolean, news: Boolean, onClick: () -> Unit, enabled: Boolean = true, shortcut: String? = null, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.hovered()
    val pill by animateDpAsState(when {
        selected -> 36.dp
        hovered && enabled -> 20.dp
        news -> 8.dp
        else -> 0.dp
    })
    val shape = if (selected || hovered) Theme[shapes][largeShape] else PillShape
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Box(Modifier.align(Alignment.CenterStart).width(4.dp).height(pill).background(color(onBackgroundColor), RoundedCornerShape(topEnd = Radius.extraSmall, bottomEnd = Radius.extraSmall)))
        Box(Modifier.align(Alignment.Center)) { Hint(listOfNotNull(name, shortcut).joinToString(" "), TooltipSide.End) {
            Box(
                Modifier.size(48.dp).clip(shape)
                    .background(if (selected) color(selectedControlColor) else color(panelColor))
                    .hoverable(interaction)
                    .clickable(interaction, Theme[indications][defaultIndication], enabled = enabled, onClick = onClick)
                    .clearAndSetSemantics {
                        contentDescription = name
                        role = Role.Button
                        stateDescription = listOfNotNull("Selected".takeIf { selected }, "New activity".takeIf { news }).joinToString(", ")
                        if (selected) this.selected = true
                        if (!enabled) disabled()
                        onClick { onClick(); true }
                    },
                contentAlignment = Alignment.Center,
            ) { content() }
        } }
    }
}

/** Home's column: Today, the Server-wide screens, and the exact Sessions that wait on the user or run now. */
@Composable
private fun HomeColumn(state: ShellState, actions: ShellActions, bottom: Dp) {
    val view = state.view
    val byId = state.agents.associateBy { it.id }
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(leading = false, trailing = false) {
            Text("Home", Modifier.weight(1f).padding(horizontal = Spacing.space8), fontWeight = FontWeight.Bold, singleLine = true)
        }
        HorizontalSeparator()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = Spacing.space8, top = Spacing.space8, end = Spacing.space8, bottom = Spacing.space8 + bottom), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            item(key = "server") { GroupHeader("On this Server") }
            item(key = "today") {
                Pinned(Lucide.Inbox, "Today", view == Route.Home, true, actions.onToday, count = state.waiting.size.takeIf { it > 0 })
            }
            item(key = "board") {
                Pinned(Lucide.SquareKanban, "Board", view == Route.Board || view is Route.Task, state.capabilities.supports(ServerFeature.Tasks), actions.onBoard)
            }
            item(key = "server-rows") {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (state.management && state.capabilities?.features?.contains(ServerFeature.Computer) == true)
                        Pinned(Lucide.MonitorSmartphone, "Computers", view is Route.Computers, true, actions.onComputers)
                    // Routines need one of Kinuvo's own Agents; otherwise this is just Workflows.
                    val routines = state.capabilities.supports(ServerFeature.Routines) && state.agents.any { !it.external }
                    Pinned(Lucide.AlarmClock, if (routines) "Routines & Workflows" else "Workflows", (view as? Route.Settings)?.section == SettingsSection.Routines || view is Route.Workflows, true,
                        if (routines) actions.onRoutines else actions.onWorkflows)
                    Pinned(Lucide.Wrench, "Tools & MCP", (view as? Route.Settings)?.section == SettingsSection.Hub, true, actions.onTools)
                    Pinned(Lucide.BookOpen, "Knowledge", view is Route.Knowledge, state.capabilities.supports(ServerFeature.Knowledge), actions.onKnowledge)
                    if (state.capabilities.supports(ServerFeature.Artifacts)) Pinned(Lucide.Shapes, "Artifacts", view is Route.Artifacts, true, actions.onArtifacts)
                }
            }
            item(key = "teams") { GroupHeader("Teams") }
            items(state.teams, key = { "team:${it.id}" }) { team ->
                ListRow((view as? Route.Team)?.id == team.id, { actions.onTeam(team.id) }, leading = { Icon(Lucide.Users, Modifier.size(16.dp), tint = color(mutedColor)) }) {
                    Text(team.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                }
            }
            item(key = "new-team") {
                ListRow(view == Route.Team(), { actions.onTeam(null) }, leading = { Icon(Lucide.Plus, Modifier.size(16.dp), tint = color(mutedColor)) }) {
                    Text("New Team", Modifier.weight(1f), color = color(mutedColor), singleLine = true)
                }
            }
            fun LazyListScope.sessions(title: String, sessions: List<SessionInfo>) {
                if (sessions.isEmpty()) return
                item(key = "group:$title") { GroupHeader(title) }
                items(sessions, key = { "$title:${it.id}" }) { s ->
                    val selected = (view as? Route.Chat)?.sessionId == s.id
                    ListRow(selected, { actions.onOpenSession(s.agentId, s.id) }, leading = { AgentAvatar(byId[s.agentId], 18.dp) }) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, singleLine = true, overflow = TextOverflow.Ellipsis)
                            Text(byId[s.agentId]?.name ?: s.agentId.value, style = TextStyles.caption, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
                        }
                        SessionStatusIcon(s.status, false)
                    }
                }
            }
            sessions("Needs you", state.waiting)
            sessions("Running", state.running)
        }
    }
}

/**
 * The shown Agent's column: its settings and a new Session by its name, a search of its Sessions, then Favorites, recent
 * Sessions with each Routine's runs folded under the latest, the Routines continuing one Session, and older Sessions.
 */
@Composable
private fun AgentColumn(state: ShellState, actions: ShellActions, chrome: ShellChrome, bottom: Dp = Spacing.none) {
    val list = rememberLazyListState()
    val inbox = state.inbox
    val settledChats = state.settledChats
    val favorites = state.favorites
    val attached = state.attached
    val query = chrome.query.text.toString().trim()
    val searching = query.isNotEmpty()
    // Only pages once every chat is listed, or the few recent ones would load them all. Searching pages through the rest.
    val paging = state.hasMore && (inbox.isEmpty() || chrome.allChats || searching || chrome.showSettled)
    // Restarts per page, since a page of only attached Sessions adds no rows to scroll to.
    LaunchedEffect(list, paging, state.sessions.size, searching) {
        if (searching && paging) actions.onLoadMore()
        else snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 }
            .collect { atEnd -> if (atEnd && paging) actions.onLoadMore() }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(leading = false, trailing = false) {
            // A long name gets a second, tight line rather than losing its end.
            Text(state.agent?.name ?: "…", Modifier.weight(1f).padding(horizontal = Spacing.space8), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyles.title.copy(lineHeight = 20.sp))
            HeaderButton(Lucide.Settings2, "Agent settings", actions.onAgentSettings, enabled = state.agent != null, plain = true)
            HeaderButton(Lucide.SquarePen, "New Session ${shortcut("N")}", actions.onNew, enabled = state.agent != null, plain = true)
        }
        HorizontalSeparator()
        SessionSearch(chrome.query, state.agent?.name, Modifier.padding(start = Spacing.space8, top = Spacing.space8, end = Spacing.space8))
        LazyColumn(Modifier.weight(1f), list, contentPadding = PaddingValues(start = Spacing.space8, top = Spacing.space4, end = Spacing.space8, bottom = Spacing.space8 + bottom), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val selected = (state.view as? Route.Chat)?.sessionId
            val attachedIds = attached.mapNotNull { it.sessionId }.toSet()
            fun settleOf(s: SessionInfo) = if (s.favorite || s.id in attachedIds || s.status != SessionStatus.Idle && !state.isSettled(s)) null
                else state.isSettled(s).let { settled -> SettleAction(settled) { actions.onSettle(s.id, !settled) } }
            fun LazyListScope.rows(sessions: List<SessionInfo>, prefix: String = "") = items(sessions, key = { prefix + it.id }) { session ->
                SessionRow(session, session.id == selected, session.id in state.finished, settleOf(session)) { actions.onSelect(session.id.value) }
            }
            if (searching) {
                val found = (favorites + inbox + settledChats + state.sessions.filter { s -> attached.any { it.sessionId == s.id } }).distinctBy { it.id }.search(query)
                item(key = "results") { GroupHeader(if (state.hasMore) "Searching older sessions…" else "${found.size} found") }
                rows(found)
                if (found.isEmpty() && !state.hasMore) item(key = "none") {
                    Text("No sessions match", Modifier.padding(horizontal = Spacing.space10, vertical = Spacing.space12), color = color(mutedColor), style = TextStyles.secondary)
                }
                return@LazyColumn
            }
            // A collapsed section keeps only the open Session, when it's in there.
            fun LazyListScope.section(section: ShellSection, count: Int? = null): Boolean {
                val collapsed = section in chrome.collapsed
                item(key = section) {
                    GroupHeader(section.title, collapsed, count) {
                        chrome.collapsed = if (collapsed) chrome.collapsed - section else chrome.collapsed + section
                    }
                }
                return collapsed
            }
            fun <T> List<T>.folded(collapsed: Boolean, id: (T) -> SessionId?) = if (collapsed) filter { id(it) == selected } else this
            if (favorites.isNotEmpty()) rows(favorites.folded(section(ShellSection.Favorites)) { it.id })
            val waiting = inbox.filter { it.status == SessionStatus.Waiting }
            val working = inbox.filter { it.status == SessionStatus.Running }
            val idle = inbox.filter { it.status == SessionStatus.Idle }
            if (waiting.isNotEmpty()) rows(waiting.folded(section(ShellSection.NeedsYou)) { it.id })
            if (working.isNotEmpty()) rows(working.folded(section(ShellSection.Working)) { it.id })
            if (idle.isNotEmpty()) {
                val collapsed = section(ShellSection.Open)
                val groups = idle.groupRoutineRuns().let { g -> g.filter { it.session.id in state.finished } + g.filter { it.session.id !in state.finished } }
                val more = groups.size > RECENT_CHATS || state.hasMore
                val shown = when {
                    collapsed -> groups.filter { g -> (listOf(g.session) + g.earlier).any { it.id == selected } }
                    chrome.allChats -> groups
                    else -> groups.take(RECENT_CHATS) + groups.drop(RECENT_CHATS).filter { g -> (listOf(g.session) + g.earlier).any { it.id == selected } }
                }
                shown.forEach { group ->
                    val key = group.session.routine?.id
                    val open = key != null && (key in chrome.expanded || group.earlier.any { it.id == selected })
                    item(key = group.session.id.value) {
                        SessionRow(group.session, group.session.id == selected, group.session.id in state.finished, settleOf(group.session)) { actions.onSelect(group.session.id.value) }
                    }
                    if (key != null && group.earlier.isNotEmpty()) {
                        item(key = "earlier:$key") {
                            ListRow(false, { chrome.expanded = if (open) chrome.expanded - key else chrome.expanded + key }, Modifier.padding(start = Spacing.space16),
                                leading = { Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, Modifier.size(14.dp)) }) {
                                Text("${group.earlier.size} earlier", Modifier.weight(1f), color = color(mutedColor), style = TextStyles.secondary, singleLine = true)
                            }
                        }
                        if (open) items(group.earlier, key = { it.id.value }) { run ->
                            Box(Modifier.padding(start = Spacing.space16)) { SessionRow(run, run.id == selected, run.id in state.finished, settleOf(run)) { actions.onSelect(run.id.value) } }
                        }
                    }
                }
                if (!collapsed && !chrome.allChats && more) item(key = "allChats") {
                    ListRow(false, { chrome.allChats = true }, leading = { Icon(Lucide.History, Modifier.size(16.dp), tint = color(mutedColor)) }) {
                        Text("Older sessions", Modifier.weight(1f), color = color(mutedColor), singleLine = true)
                    }
                }
            } else if (state.sessions.isNotEmpty() && waiting.isEmpty() && working.isEmpty()) item(key = "clear") { EmptyNote("Inbox clear") }
            if (attached.isNotEmpty()) {
                val byId = state.sessions.associateBy { it.id }
                items(attached.folded(section(ShellSection.Routines)) { it.sessionId }, key = { "routine:${it.id}" }) { r ->
                    val session = byId[r.sessionId]
                    ShellRow(Lucide.AlarmClock, r.name, session?.status ?: SessionStatus.Idle, r.sessionId == selected, r.sessionId in state.finished) {
                        actions.onSelect(r.sessionId!!.value)
                    }
                }
            }
            // Settled Sessions wait at the very end, folded until opened, and leave when there's news in one.
            if (settledChats.isNotEmpty()) {
                item(key = "group:settled") { GroupHeader("Settled", !chrome.showSettled, settledChats.size) { chrome.showSettled = !chrome.showSettled } }
                rows(settledChats.folded(!chrome.showSettled) { it.id }, "settled:")
            }
            if (state.sessions.isEmpty() && state.agent != null) item(key = "empty") {
                EmptyNote("No sessions yet")
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) = Text(text, Modifier.padding(horizontal = Spacing.space10, vertical = Spacing.space12), color = color(mutedColor), style = TextStyles.secondary)

/** Filters the Agent's Sessions by title. */
@Composable
private fun SessionSearch(query: TextFieldState, agent: String?, modifier: Modifier = Modifier) {
    val shape = Theme[shapes][mediumShape]
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(shape).background(color(backgroundColor).copy(alpha = 0.6f), shape).border(1.dp, color(borderColor), shape).padding(horizontal = Spacing.space10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
    ) {
        Icon(Lucide.Search, Modifier.size(16.dp), tint = color(mutedColor))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.text.isEmpty()) Text("Search Sessions", style = inputTextStyle(), color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
            BasicTextField(query, Modifier.fillMaxWidth().semantics { contentDescription = "Search sessions" }, textStyle = TextStyle(fontSize = TextStyles.callout.fontSize, color = color(onBackgroundColor)),
                lineLimits = TextFieldLineLimits.SingleLine, cursorBrush = SolidColor(color(accentColor)))
        }
        if (query.text.isNotEmpty()) FieldButton(Lucide.X, "Clear search", { query.clearText() })
    }
}

@Composable
private fun MenuLabel(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
    Icon(icon, Modifier.size(16.dp))
    Text(text)
}

@Composable
private fun Pinned(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, count: Int? = null) =
    ListRow(selected, onClick, enabled = enabled, leading = { Icon(icon, Modifier.size(16.dp)) }) {
        Text(label, Modifier.weight(1f), singleLine = true)
        count?.let { Badge("$it") }
    }

/** A section's heading; with [onToggle], it collapses the section. */
@Composable
private fun GroupHeader(title: String, collapsed: Boolean = false, count: Int? = null, onToggle: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.space12).clip(Theme[shapes][smallShape])
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle).semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" } else Modifier)
            .heightIn(min = if (onToggle != null) 36.dp else 0.dp).padding(horizontal = Spacing.space6, vertical = Spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
    ) {
        Text(if (count != null) "$title ($count)" else title, style = TextStyles.secondary, color = color(mutedColor), singleLine = true)
        HorizontalSeparator(Modifier.weight(1f))
        if (onToggle != null) Icon(if (collapsed) Lucide.ChevronDown else Lucide.ChevronUp, Modifier.size(14.dp), tint = color(mutedColor))
    }
}

/** What a Session row offers to move it out of, or back into, the inbox. */
private class SettleAction(val settled: Boolean, val onClick: () -> Unit)

@Composable
private fun SessionRow(session: SessionInfo, selected: Boolean, finished: Boolean, settle: SettleAction? = null, onClick: () -> Unit) {
    val icon = when {
        session.task != null -> Lucide.SquareKanban
        session.routine != null -> Lucide.AlarmClock
        else -> Lucide.Hash
    }
    ShellRow(icon, session.title, session.status, selected, finished, settle, onClick)
}

@Composable
private fun ShellRow(icon: ImageVector, title: String, status: SessionStatus, selected: Boolean, finished: Boolean, settle: SettleAction? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered = source.hovered()
    val unread = finished && status == SessionStatus.Idle
    val row = @Composable {
        ListRow(selected, onClick, Modifier.hoverable(source), leading = { Icon(icon, Modifier.size(16.dp)) }) {
            Text(title.trimEnd(), Modifier.weight(1f), style = TextStyles.label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (unread) FontWeight.Bold else null,
                // Idle Sessions recede, but stay clearly readable rather than looking disabled.
                color = color(onBackgroundColor).copy(alpha = if (selected || finished || status != SessionStatus.Idle) 1f else 0.75f))
            when {
                settle != null && hovered -> SettleButton(settle, title.trim())
                unread -> Box(Modifier.size(8.dp).background(color(accentColor), CircleShape).semantics { contentDescription = "Unread" })
                else -> SessionStatusIcon(status, finished)
            }
        }
    }
    if (settle != null && LocalInteractionMode.current == InteractionMode.Touch) SwipeToSettle(settle, row) else row()
}

@Composable
private fun SettleButton(settle: SettleAction, title: String) {
    val label = if (settle.settled) "Unsettle $title" else "Settle $title"
    Box(Modifier.size(24.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = settle.onClick), contentAlignment = Alignment.Center) {
        Icon(if (settle.settled) Lucide.Undo2 else Lucide.Check, Modifier.size(15.dp), tint = color(mutedColor), contentDescription = label)
    }
}

/** Phones settle a row, or put it back, by swiping it toward the end past a third of its width. */
@Composable
private fun SwipeToSettle(settle: SettleAction, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val shape = Theme[shapes][mediumShape]
    Box(Modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        if (offset.value > 0f) Box(Modifier.matchParentSize().clip(shape).background(color(successColor).copy(alpha = 0.2f)).padding(start = Spacing.space16), contentAlignment = Alignment.CenterStart) {
            Icon(if (settle.settled) Lucide.Undo2 else Lucide.Check, Modifier.size(18.dp), tint = color(successColor))
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.then(if (offset.value > 0f) Modifier.background(color(sidebarColor), shape) else Modifier)
                .draggable(rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) } }, Orientation.Horizontal,
                    onDragStopped = {
                        scope.launch {
                            if (offset.value > width / 3) { offset.animateTo(width); settle.onClick() }
                            offset.animateTo(0f)
                        }
                    }),
        ) { content() }
    }
}

private const val RECENT_CHATS = 4

/** The column's collapsible sections, under its pinned screens and chats. */
enum class ShellSection(val title: String) { Favorites("Favorites"), NeedsYou("Needs you"), Working("Working"), Open("Open"), Routines("Routines") }

@Composable
private fun ServerPicker(
    menu: ServerMenuState,
    actions: ShellActions,
) {
    var open by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            DropdownMenuPanel {
                Column(Modifier.widthIn(min = 260.dp, max = 340.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
                    // Adding leads, so it never scrolls away below a long list.
                    DropdownMenuItem(onClick = { open = false; actions.onAddServer() }) { MenuLabel(Lucide.Plus, "Connect to another server") }
                    DropdownMenuLabel { Text("Servers", style = TextStyles.caption) }
                    menu.servers.forEach { server ->
                        val active = server == menu.active
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DropdownMenuItem(
                                onClick = { open = false; if (!active) actions.onSelectServer(server) },
                                modifier = Modifier.weight(1f),
                                trailing = if (active) ({ Icon(Lucide.Check, Modifier.size(14.dp)) }) else null,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                                    if (server is Server.Embedded) Icon(Lucide.Server, Modifier.size(16.dp))
                                    Text(server.label, singleLine = true, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (server is Server.Remote) {
                                IconButton(onClick = { open = false; actions.onServerSettings(server) }, style = ButtonStyle.Ghost) {
                                    Icon(Lucide.Settings, Modifier.size(16.dp), contentDescription = "Settings for ${server.label}")
                                }
                            }
                        }
                    }
                }
            }
        },
    ) {
        RailItem("Server: ${menu.active?.label ?: "none"}", selected = false, news = false, onClick = { open = true }) {
            Icon(Lucide.Server, Modifier.size(20.dp), tint = color(mutedColor))
        }
    }
}
