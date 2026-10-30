package kinuvo.ui.navigation

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.navigation.*
import kinuvo.ui.connection.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
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
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.Routine
import kinuvo.protocol.ServerCapabilities
import kinuvo.protocol.ServerFeature
import kinuvo.protocol.supports
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionStatus
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
    val lists: Map<String, SessionList> = emptyMap(),
    val finished: Set<String> = emptySet(),
    val view: Route? = null,
    val servers: ServerMenuState = ServerMenuState(),
    val capabilities: ServerCapabilities? = null,
    val routines: Map<String, List<Routine>> = emptyMap(),
    /** Whether this Device has management access, which watching and taking over Computers needs. */
    val management: Boolean = true,
    val home: Boolean = false,
) {
    val sessions get() = agent?.let { lists[it.id] }?.sessions.orEmpty()
    val hasMore get() = agent?.let { lists[it.id] }?.hasMore == true
    /** Routines that continue one Session, listed under Routines so it stays at hand. */
    val attached get() = agent?.let { routines[it.id] }.orEmpty().filter { it.sessionId != null }
    private val unattached get() = attached.mapNotNull { it.sessionId }.toSet().let { ids -> sessions.filter { it.id !in ids } }
    val favorites get() = unattached.filter { it.favorite }
    val chats get() = unattached.filter { !it.favorite }
    val settings get() = view is Route.Settings && !view.home
    private val everySession get() = lists.values.flatMap { it.sessions }.distinctBy { it.id }
    /** Sessions across Agents waiting on an Approval or an answer, and those running, for Home's column. */
    val waiting get() = everySession.filter { it.status == SessionStatus.Waiting }
    val running get() = everySession.filter { it.status == SessionStatus.Running }

    /** An Agent has news when one of its Sessions runs, waits on the user or finished while elsewhere. */
    fun hasNews(agentId: String) = lists[agentId]?.sessions.orEmpty().any { it.status != SessionStatus.Idle || it.id in finished }

    fun avatarState(agentId: String) = kinuvo.app.agent.avatarState(lists[agentId]?.sessions.orEmpty().map { it.status })
}

class ShellActions(
    val onSelectAgent: (String) -> Unit = {},
    val onAddAgent: () -> Unit = {},
    val onNew: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onLoadMore: () -> Unit = {},
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
    val onTools: () -> Unit = {},
    /** Opens one Agent's Session from Home's column. */
    val onOpenSession: (agentId: String, sessionId: String) -> Unit = { _, _ -> },
    val onSelectServer: (Server) -> Unit = {},
    val onAddServer: () -> Unit = {},
    /** Opens a saved Server's settings. */
    val onServerSettings: (Server.Remote) -> Unit = {},
    val onRenameServer: (Server.Remote, String) -> Unit = { _, _ -> },
    val onForgetServer: (Server.Remote) -> Unit = {},
    /** Starts a Session with the shown Agent by sending the text, opening it when asked; false when it couldn't. */
    val onQuickSend: suspend (agentId: String, text: String, open: Boolean) -> Boolean = { _, _, _ -> false },
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
    /** Every chat, loading more on scroll, rather than the few most recent. */
    var allChats by mutableStateOf(false)

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
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(topStart = 16.dp)).background(color(sidebarColor))) { content() }

@Composable
private fun ContextColumn(state: ShellState, actions: ShellActions, chrome: ShellChrome, bottom: Dp = 0.dp) =
    if (state.home) HomeColumn(state, actions, bottom) else AgentColumn(state, actions, chrome, bottom)

/** Phones, edge to edge: the same rail and column, with the bar opening [QuickChat] floating over their foot. */
@Composable
private fun PhoneShell(state: ShellState, actions: ShellActions, chrome: ShellChrome, modifier: Modifier) {
    val insets = WindowInsets.safeDrawing
    // Room under the lists for the bar floating over their foot.
    val clear = QuickChatBarHeight + insets.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().background(color(railColor)).windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxSize()) {
            OwnerRail(state, actions, Modifier.windowInsetsPadding(insets.only(WindowInsetsSides.Bottom)), top = 8.dp)
            ColumnPanel(Modifier.weight(1f)) { ContextColumn(state, actions, chrome, bottom = clear) }
        }
        QuickChatBar(state.agent, chrome.draft, Modifier.align(Alignment.BottomStart).padding(start = RailWidth).windowInsetsPadding(insets.only(WindowInsetsSides.Bottom))) { chrome.quickChat = true }
    }
}

/** Home at the top, the owners scrolling between dividers, and the Server and Settings fixed at the foot. */
@Composable
private fun OwnerRail(state: ShellState, actions: ShellActions, modifier: Modifier = Modifier, top: Dp? = null) {
    Column(
        // A touch wider where the window buttons sit over it, so they clear the column.
        modifier.width(if ((LocalWindowChrome.current?.leadingInset ?: 0.dp) > 0.dp) 80.dp else RailWidth).fillMaxHeight().background(color(railColor))
            .padding(top = top ?: LocalWindowChrome.current?.height ?: 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RailItem("Home", selected = state.home && !state.settings, news = state.waiting.isNotEmpty(), onClick = actions.onHome) {
            Icon(Lucide.House, Modifier.size(20.dp), tint = color(if (state.home && !state.settings) onBackgroundColor else mutedColor))
        }
        HorizontalSeparator(Modifier.width(32.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val hovered by interaction.collectIsHoveredAsState()
    val pill by animateDpAsState(when {
        selected -> 36.dp
        hovered && enabled -> 20.dp
        news -> 8.dp
        else -> 0.dp
    })
    val shape = if (selected || hovered) Theme[shapes][largeShape] else RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Box(Modifier.align(Alignment.CenterStart).width(4.dp).height(pill).background(color(onBackgroundColor), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        Box(Modifier.align(Alignment.Center)) { Tooltip(side = TooltipSide.End, panel = { TooltipPanel { Text(listOfNotNull(name, shortcut).joinToString(" "), fontSize = 12.sp) } }) {
            Box(
                Modifier.size(48.dp).clip(shape)
                    .background(if (selected) color(selectedControlColor) else color(panelColor))
                    .hoverable(interaction)
                    .clickable(interaction, Theme[indications][defaultIndication], enabled = enabled, onClick = onClick)
                    .clearAndSetSemantics {
                        contentDescription = name
                        role = Role.Button
                        stateDescription = if (selected) "Selected" else ""
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
            Text("Home", Modifier.weight(1f).padding(horizontal = 8.dp), fontWeight = FontWeight.Bold, singleLine = true)
        }
        HorizontalSeparator()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + bottom), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            item(key = "today") {
                Pinned(Lucide.Inbox, "Today", view == Route.Home, true, actions.onToday, count = state.waiting.size.takeIf { it > 0 })
            }
            item(key = "board") {
                Pinned(Lucide.SquareKanban, "Board", view == Route.Board || view is Route.Task, state.capabilities.supports(ServerFeature.Tasks), actions.onBoard)
            }
            item(key = "server") { GroupHeader("On this Server") }
            item(key = "server-rows") {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (state.management && state.capabilities?.features?.contains(ServerFeature.Computer) == true)
                        Pinned(Lucide.MonitorSmartphone, "Computers", view is Route.Computers, true, actions.onComputers)
                    Pinned(Lucide.AlarmClock, "Routines", (view as? Route.Settings)?.section == SettingsSection.Routines, state.capabilities.supports(ServerFeature.Routines), actions.onRoutines)
                    Pinned(Lucide.Wrench, "Tools & MCP", (view as? Route.Settings)?.section == SettingsSection.Hub, true, actions.onTools)
                    Pinned(Lucide.BookOpen, "Knowledge", view is Route.Knowledge, state.capabilities.supports(ServerFeature.Knowledge), actions.onKnowledge)
                    Pinned(Lucide.Shapes, "Artifacts", view is Route.Artifacts, state.capabilities.supports(ServerFeature.Artifacts), actions.onArtifacts)
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
                            Text(byId[s.agentId]?.name ?: s.agentId, fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
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
private fun AgentColumn(state: ShellState, actions: ShellActions, chrome: ShellChrome, bottom: Dp = 0.dp) {
    val list = rememberLazyListState()
    val chats = state.chats
    val favorites = state.favorites
    val attached = state.attached
    val query = chrome.query.text.toString().trim()
    val searching = query.isNotEmpty()
    // Only pages once every chat is listed, or the few recent ones would load them all. Searching pages through the rest.
    val paging = state.hasMore && (chats.isEmpty() || chrome.allChats || searching)
    // Restarts per page, since a page of only attached Sessions adds no rows to scroll to.
    LaunchedEffect(list, paging, state.sessions.size, searching) {
        if (searching && paging) actions.onLoadMore()
        else snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 }
            .collect { atEnd -> if (atEnd && paging) actions.onLoadMore() }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(leading = false, trailing = false) {
            Text(state.agent?.name ?: "…", Modifier.weight(1f).padding(horizontal = 8.dp), fontWeight = FontWeight.Bold, singleLine = true, overflow = TextOverflow.Ellipsis)
            HeaderButton(Lucide.Settings2, "Agent settings", actions.onAgentSettings, enabled = state.agent != null)
            HeaderButton(Lucide.SquarePen, "New session ${shortcut("N")}", actions.onNew, enabled = state.agent != null)
        }
        HorizontalSeparator()
        SessionSearch(chrome.query, state.agent?.name, Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
        LazyColumn(Modifier.weight(1f), list, contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 8.dp + bottom), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val selected = (state.view as? Route.Chat)?.sessionId
            fun LazyListScope.rows(sessions: List<SessionInfo>, prefix: String = "") = items(sessions, key = { prefix + it.id }) { session ->
                SessionRow(session, session.id == selected, session.id in state.finished) { actions.onSelect(session.id) }
            }
            if (searching) {
                val found = (favorites + chats + state.sessions.filter { s -> attached.any { it.sessionId == s.id } }).distinctBy { it.id }.search(query)
                item(key = "results") { GroupHeader(if (state.hasMore) "Searching older sessions…" else "${found.size} found") }
                rows(found)
                if (found.isEmpty() && !state.hasMore) item(key = "none") {
                    Text("No sessions match", Modifier.padding(horizontal = 10.dp, vertical = 12.dp), color = color(mutedColor), fontSize = 13.sp)
                }
                return@LazyColumn
            }
            fun needsLook(sessions: List<SessionInfo>) = sessions.filter { it.id == selected || it.status != SessionStatus.Idle || it.id in state.finished }
            if (favorites.isNotEmpty()) {
                val collapsed = ShellSection.Favorites in chrome.collapsed
                item(key = ShellSection.Favorites) {
                    GroupHeader(ShellSection.Favorites.title, collapsed) {
                        chrome.collapsed = if (collapsed) chrome.collapsed - ShellSection.Favorites else chrome.collapsed + ShellSection.Favorites
                    }
                }
                rows(if (collapsed) needsLook(favorites) else favorites)
            }
            if (chats.isNotEmpty()) {
                val groups = chats.groupRoutineRuns()
                val more = groups.size > RECENT_CHATS || state.hasMore
                item(key = "chats") { GroupHeader("Sessions", !chrome.allChats, if (more) ({ chrome.allChats = !chrome.allChats }) else null) }
                val shown = if (chrome.allChats) groups else groups.take(RECENT_CHATS) + groups.drop(RECENT_CHATS).filter { g -> needsLook(listOf(g.session) + g.earlier).isNotEmpty() }
                shown.forEach { group ->
                    val key = group.session.routine?.id
                    val open = key != null && (key in chrome.expanded || group.earlier.any { it.id == selected })
                    item(key = group.session.id) {
                        SessionRow(group.session, group.session.id == selected, group.session.id in state.finished) { actions.onSelect(group.session.id) }
                    }
                    if (key != null && group.earlier.isNotEmpty()) {
                        item(key = "earlier:$key") {
                            ListRow(false, { chrome.expanded = if (open) chrome.expanded - key else chrome.expanded + key }, Modifier.padding(start = 16.dp),
                                leading = { Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, Modifier.size(14.dp)) }) {
                                Text("${group.earlier.size} earlier", Modifier.weight(1f), color = color(mutedColor), fontSize = 13.sp, singleLine = true)
                            }
                        }
                        if (open) items(group.earlier, key = { it.id }) { run ->
                            Box(Modifier.padding(start = 16.dp)) { SessionRow(run, run.id == selected, run.id in state.finished) { actions.onSelect(run.id) } }
                        }
                    }
                }
                if (!chrome.allChats && more) item(key = "allChats") {
                    ListRow(false, { chrome.allChats = true }, leading = { Icon(Lucide.History, Modifier.size(16.dp), tint = color(mutedColor)) }) {
                        Text("Older sessions", Modifier.weight(1f), color = color(mutedColor), singleLine = true)
                    }
                }
            }
            // Collapsed, it lists only what needs a look: the open Session, runs, waits and unread results.
            if (attached.isNotEmpty()) {
                val collapsed = ShellSection.Routines in chrome.collapsed
                val byId = state.sessions.associateBy { it.id }
                item(key = ShellSection.Routines) {
                    GroupHeader(ShellSection.Routines.title, collapsed) {
                        chrome.collapsed = if (collapsed) chrome.collapsed - ShellSection.Routines else chrome.collapsed + ShellSection.Routines
                    }
                }
                val shown = if (!collapsed) attached else attached.filter { r -> byId[r.sessionId]?.let { needsLook(listOf(it)).isNotEmpty() } ?: (r.sessionId == selected) }
                items(shown, key = { "routine:${it.id}" }) { r ->
                    val session = byId[r.sessionId]
                    ShellRow(Lucide.AlarmClock, r.name, session?.status ?: SessionStatus.Idle, r.sessionId == selected, r.sessionId in state.finished) {
                        actions.onSelect(r.sessionId!!)
                    }
                }
            }
            if (state.sessions.isEmpty() && state.agent != null) item(key = "empty") {
                Text("No sessions yet", Modifier.padding(horizontal = 10.dp, vertical = 12.dp), color = color(mutedColor), fontSize = 13.sp)
            }
        }
    }
}

/** Filters the Agent's Sessions by title. */
@Composable
private fun SessionSearch(query: TextFieldState, agent: String?, modifier: Modifier = Modifier) {
    val shape = Theme[shapes][mediumShape]
    Row(
        modifier.fillMaxWidth().height(34.dp).clip(shape).background(color(backgroundColor).copy(alpha = 0.6f), shape).border(1.dp, color(borderColor), shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Search, Modifier.size(14.dp), tint = color(mutedColor))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.text.isEmpty()) Text(agent?.let { "Search $it's sessions" } ?: "Search sessions", fontSize = 13.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
            BasicTextField(query, Modifier.fillMaxWidth().semantics { contentDescription = "Search sessions" }, textStyle = TextStyle(fontSize = 13.sp, color = color(onBackgroundColor)),
                lineLimits = TextFieldLineLimits.SingleLine, cursorBrush = SolidColor(color(accentColor)))
        }
        if (query.text.isNotEmpty()) Icon(Lucide.X, Modifier.size(14.dp).clickable { query.clearText() }, tint = color(mutedColor), contentDescription = "Clear search")
    }
}

@Composable
private fun MenuLabel(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun GroupHeader(title: String, collapsed: Boolean = false, onToggle: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(Theme[shapes][smallShape])
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle).semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" } else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title.uppercase(), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp, color = color(mutedColor), singleLine = true)
        if (onToggle != null) Icon(if (collapsed) Lucide.ChevronRight else Lucide.ChevronDown, Modifier.size(12.dp), tint = color(mutedColor))
    }
}

@Composable
private fun SessionRow(session: SessionInfo, selected: Boolean, finished: Boolean, onClick: () -> Unit) {
    val icon = when {
        session.task != null -> Lucide.SquareKanban
        session.routine != null -> Lucide.AlarmClock
        else -> Lucide.Hash
    }
    ShellRow(icon, session.title, session.status, selected, finished, onClick)
}

@Composable
private fun ShellRow(icon: ImageVector, title: String, status: SessionStatus, selected: Boolean, finished: Boolean, onClick: () -> Unit) {
    ListRow(selected, onClick, leading = { Icon(icon, Modifier.size(16.dp)) }) {
        Text(title, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis,
            color = if (selected || finished || status != SessionStatus.Idle) color(onBackgroundColor) else color(mutedColor))
        SessionStatusIcon(status, finished)
    }
}

private const val RECENT_CHATS = 4

/** The column's collapsible sections, under its pinned screens and chats. */
enum class ShellSection(val title: String) { Favorites("Favorites"), Routines("Routines") }

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
                    DropdownMenuLabel { Text("Servers", fontSize = 12.sp) }
                    menu.servers.forEach { server ->
                        val active = server == menu.active
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DropdownMenuItem(
                                onClick = { open = false; if (!active) actions.onSelectServer(server) },
                                modifier = Modifier.weight(1f),
                                trailing = if (active) ({ Icon(Lucide.Check, Modifier.size(14.dp)) }) else null,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    DropdownMenuItem(onClick = { open = false; actions.onAddServer() }) { MenuLabel(Lucide.Plus, "Connect to another server") }
                }
            }
        },
    ) {
        RailItem("Server: ${menu.active?.label ?: "none"}", selected = false, news = false, onClick = { open = true }) {
            Icon(Lucide.Server, Modifier.size(20.dp), tint = color(mutedColor))
        }
    }
}
