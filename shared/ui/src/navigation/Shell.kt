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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
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
import kinuvo.app.rooms.*
import kinuvo.protocol.Room
import kinuvo.protocol.RoomSession
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
 * The app shell: the rail of Agents and the shown [agent]'s column of pinned screens and Sessions. [view] is the screen
 * open beside it, to highlight.
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
    val rooms: RoomsState = RoomsState(),
) {
    val sessions get() = agent?.let { lists[it.id] }?.sessions.orEmpty()
    val hasMore get() = agent?.let { lists[it.id] }?.hasMore == true
    /** Routines that continue one Session, listed under Routines so it stays at hand. */
    val attached get() = agent?.let { routines[it.id] }.orEmpty().filter { it.sessionId != null }
    private val unattached get() = attached.mapNotNull { it.sessionId }.toSet().let { ids -> sessions.filter { it.id !in ids } }
    val favorites get() = unattached.filter { it.favorite }
    val chats get() = unattached.filter { !it.favorite }

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
    val onRooms: () -> Unit = {},
    val onSelectRoom: (Room) -> Unit = {},
    val onRoomSession: (RoomSession) -> Unit = {},
    val onNewRoom: () -> Unit = {},
    val onBoard: () -> Unit = {},
    val onKnowledge: () -> Unit = {},
    val onArtifacts: () -> Unit = {},
    val onComputers: () -> Unit = {},
    val onSelectServer: (Server) -> Unit = {},
    val onAddServer: () -> Unit = {},
    /** Opens a saved Server's settings. */
    val onServerSettings: (Server.Remote) -> Unit = {},
    val onRefreshPairing: (Boolean) -> Unit = {},
    val onPairingBase: (String) -> Unit = {},
    val onRenameServer: (Server.Remote, String) -> Unit = { _, _ -> },
    val onForgetServer: (Server.Remote) -> Unit = {},
    /** Starts a Session with the shown Agent by sending the text, opening it when asked; false when it couldn't. */
    val onQuickSend: suspend (agentId: String, text: String, open: Boolean) -> Boolean = { _, _, _ -> false },
)

/**
 * Shell layout state that outlives screens: whether it's [open] beside them on wide layouts or [revealed] from under them
 * on phones, whether phones show the [quickChat] and its [draft], which [ShellSection]s are [collapsed], remembered in
 * [prefs] across launches, and whether [allChats] are listed.
 */
@Stable
class ShellChrome(open: MutableState<Boolean>, private val prefs: Preferences? = null) {
    var open by open
    var revealed by mutableStateOf(false)
    var quickChat by mutableStateOf(false)
    val draft = TextFieldState()
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
    var membershipCollapsed by mutableStateOf(false)

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

/** The rail and the Agent's column. [fill] lays it out for phones, where it's revealed from under the screens. */
@Composable
fun Shell(state: ShellState, actions: ShellActions, chrome: ShellChrome, modifier: Modifier = Modifier, fill: Boolean = false) {
    if (fill) PhoneShell(state, actions, chrome, modifier)
    else Row(modifier.fillMaxHeight().background(color(railColor))) {
        AgentRail(state, actions)
        ColumnPanel(Modifier.width(ColumnWidth)) { NavigationColumn(state, actions, chrome) }
    }
}

private val ColumnWidth = 240.dp
internal val RailWidth = 72.dp

/** The column sits in the rail with a rounded corner, Discord-style, instead of a straight seam. */
@Composable
private fun ColumnPanel(modifier: Modifier, content: @Composable () -> Unit) =
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(topStart = 16.dp)).background(color(sidebarColor))) { content() }

/** Phones reveal the same Rooms and Agent navigation above the quick chat bar. */
@Composable
private fun PhoneShell(state: ShellState, actions: ShellActions, chrome: ShellChrome, modifier: Modifier) {
    val insets = WindowInsets.safeDrawing
    // Room under the lists for the bar floating over their foot.
    val clear = QuickChatBarHeight + insets.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().background(color(railColor)).windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.padding(bottom = clear)) { AgentRail(state, actions) }
            ColumnPanel(Modifier.weight(1f)) { NavigationColumn(state, actions, chrome, bottom = clear) }
        }
        QuickChatBar(state.agent, chrome.draft, Modifier.align(Alignment.BottomStart).windowInsetsPadding(insets.only(WindowInsetsSides.Bottom))) { chrome.quickChat = true }
    }
}

@Composable
private fun AgentRail(state: ShellState, actions: ShellActions) {
    Column(
        Modifier.width(RailWidth).fillMaxHeight().background(color(railColor)).padding(top = LocalWindowChrome.current?.height ?: 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoomsRailItem(state, actions)
        HorizontalSeparator(Modifier.width(32.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.agents.forEachIndexed { i, agent ->
                val selected = state.view !is Route.Rooms && agent.id == state.agent?.id
                RailItem(if (i < 9) "${agent.name} ${shortcut("${i + 1}", shift = true)}" else agent.name, selected, news = state.hasNews(agent.id), onClick = { actions.onSelectAgent(agent.id) }) {
                    AgentAvatar(agent, 40.dp, state = state.avatarState(agent.id))
                }
            }
            HorizontalSeparator(Modifier.width(32.dp))
            RailItem("New agent", selected = false, news = false, onClick = actions.onAddAgent, enabled = state.agent != null) {
                Icon(Lucide.Plus, Modifier.size(20.dp), tint = color(successColor))
            }
        }
        var settingsMenu by remember { mutableStateOf(false) }
        DropdownMenu(expanded = settingsMenu, onExpandedChange = { settingsMenu = it }, panel = {
            DropdownMenuPanel {
                DropdownMenuItem(onClick = { settingsMenu = false; actions.onSettings() }) { Text("Settings") }
                if (state.capabilities.supports(ServerFeature.Knowledge))
                    DropdownMenuItem(onClick = { settingsMenu = false; actions.onKnowledge() }) { Text("Knowledge") }
                if (state.management && state.capabilities?.features?.contains(ServerFeature.Computer) == true)
                    DropdownMenuItem(onClick = { settingsMenu = false; actions.onComputers() }) { Text("Computers") }
            }
        }) {
            RailItem("Settings ${shortcut(",")}", selected = false, news = false, onClick = { settingsMenu = true }, enabled = state.agent != null) {
                Icon(Lucide.Settings, Modifier.size(20.dp), tint = color(mutedColor))
            }
        }
        ServerPicker(state.servers, actions)
    }
}

/** A rail button with a pill on the left edge: tall when [selected], a dot when it has [news]. */
@Composable
private fun RailItem(label: String, selected: Boolean, news: Boolean, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
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
        Box(Modifier.align(Alignment.Center)) { Tooltip(side = TooltipSide.End, panel = { TooltipPanel { Text(label, fontSize = 12.sp) } }) {
            Box(
                Modifier.size(48.dp).clip(shape)
                    .background(if (selected) color(selectedControlColor) else color(panelColor))
                    .hoverable(interaction)
                    .clickable(interaction, Theme[indications][defaultIndication], enabled = enabled, onClick = onClick)
                    .semantics { contentDescription = label; stateDescription = if (selected) "Selected" else "" },
                contentAlignment = Alignment.Center,
            ) { content() }
        } }
    }
}

@Composable
private fun RoomsRailItem(state: ShellState, actions: ShellActions) =
    RailItem("Rooms", state.view is Route.Rooms, news = false, onClick = actions.onRooms) {
        Icon(Lucide.PanelsTopLeft, Modifier.size(24.dp), tint = color(onBackgroundColor))
    }

@Composable
private fun NavigationColumn(state: ShellState, actions: ShellActions, chrome: ShellChrome, bottom: Dp = 0.dp) {
    if (state.view !is Route.Rooms) AgentColumn(state, actions, chrome, bottom)
    else Column(Modifier.fillMaxSize()) {
        TitleBarRow(leading = false, trailing = false) {
            Text("Conversations", Modifier.weight(1f).padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
            HeaderButton(Lucide.SquarePen, "New Room", actions.onNewRoom)
        }
        HorizontalSeparator()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 8.dp + bottom)) {
            item { Pinned(Lucide.SquareKanban, "Board", false, state.capabilities.supports(ServerFeature.Tasks), actions.onBoard) }
            item { Pinned(Lucide.Shapes, "Artifacts", false, state.capabilities.supports(ServerFeature.Artifacts), actions.onArtifacts) }
            item { GroupHeader("Rooms") }
            state.rooms.rooms.filterNot { it.direct }.forEach { room -> roomRows(room, state, actions) }
            if (state.rooms.rooms.none { !it.direct }) item { Text("Create a Room to work together", Modifier.padding(12.dp), color = color(mutedColor), fontSize = 13.sp) }
        }
    }
}

private fun LazyListScope.roomRows(room: Room, state: ShellState, actions: ShellActions) {
    item(key = "room:${room.id}") {
        ListRow(false, { actions.onSelectRoom(room) }, leading = { Icon(Lucide.PanelsTopLeft, Modifier.size(16.dp)) }) {
            Text(room.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
        }
    }
    items(state.rooms.roomSessions[room.id].orEmpty(), key = { "room:${room.id}/${it.id}" }) { session ->
        Box(Modifier.padding(start = 12.dp)) {
            ListRow(state.view is Route.Rooms && state.rooms.view?.session?.id == session.id, { actions.onRoomSession(session) }, leading = { Icon(Lucide.Hash, Modifier.size(16.dp)) }) {
                Text(session.displayTitle(), Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AgentColumn(state: ShellState, actions: ShellActions, chrome: ShellChrome, bottom: Dp = 0.dp) {
    val list = rememberLazyListState()
    val chats = state.chats
    val favorites = state.favorites
    val attached = state.attached
    // Only pages once every chat is listed, or the few recent ones would load them all.
    val paging = state.hasMore && (chats.isEmpty() || chrome.allChats)
    // Restarts per page, since a page of only attached Sessions adds no rows to scroll to.
    LaunchedEffect(list, paging, state.sessions.size) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 }
            .collect { atEnd -> if (atEnd && paging) actions.onLoadMore() }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(leading = false, trailing = false) {
            Text(state.agent?.name ?: "…", Modifier.weight(1f).padding(horizontal = 8.dp), fontWeight = FontWeight.Bold, singleLine = true, overflow = TextOverflow.Ellipsis)
            HeaderButton(Lucide.SquarePen, "New session ${shortcut("N")}", actions.onNew, enabled = state.agent != null)
        }
        HorizontalSeparator()
        LazyColumn(Modifier.weight(1f), list, contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + bottom), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            item(key = "pinned") {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Pinned(Lucide.SquareKanban, "Board", state.view is Route.Board, state.capabilities.supports(ServerFeature.Tasks), actions.onBoard)
                    Pinned(Lucide.Shapes, "Artifacts", state.view is Route.Artifacts, state.capabilities.supports(ServerFeature.Artifacts), actions.onArtifacts)
                }
            }
            val selected = (state.view as? Route.Chat)?.sessionId
            fun LazyListScope.rows(sessions: List<SessionInfo>) = items(sessions, key = { it.id }) { session ->
                SessionRow(session, session.id == selected, session.id in state.finished) { actions.onSelect(session.id) }
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
                val more = chats.size > RECENT_CHATS || state.hasMore
                item(key = "chats") { GroupHeader("Sessions", !chrome.allChats, onToggle = if (more) ({ chrome.allChats = !chrome.allChats }) else null) }
                rows(if (chrome.allChats) chats else chats.take(RECENT_CHATS) + needsLook(chats.drop(RECENT_CHATS)))
                if (!chrome.allChats && more) item(key = "allChats") {
                    ListRow(false, { chrome.allChats = true }, leading = { Icon(Lucide.ChevronDown, Modifier.size(16.dp), tint = color(mutedColor)) }) {
                        Text("Show more", Modifier.weight(1f), color = color(mutedColor), singleLine = true)
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
            val memberships = state.rooms.rooms.filter { !it.direct && state.agent?.id in it.members }
            if (memberships.isNotEmpty()) {
                item(key = "membership") { GroupHeader("Rooms", chrome.membershipCollapsed, description = "Rooms for ${state.agent?.name}") { chrome.membershipCollapsed = !chrome.membershipCollapsed } }
                if (!chrome.membershipCollapsed) memberships.forEach { room -> roomRows(room, state, actions) }
            }
            if (state.sessions.isEmpty() && state.agent != null) item(key = "empty") {
                Text("No sessions yet", Modifier.padding(horizontal = 10.dp, vertical = 12.dp), color = color(mutedColor), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MenuLabel(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(icon, Modifier.size(16.dp))
    Text(text)
}

@Composable
private fun Pinned(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) =
    ListRow(selected, onClick, enabled = enabled, leading = { Icon(icon, Modifier.size(16.dp)) }) { Text(label, Modifier.weight(1f), singleLine = true) }

/** A section's heading; with [onToggle], it collapses the section. */
@Composable
private fun GroupHeader(title: String, collapsed: Boolean = false, description: String? = null, onToggle: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().semantics { description?.let { contentDescription = it } }.padding(top = 12.dp).clip(Theme[shapes][smallShape])
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle).semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" } else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 12.sp, lineHeight = 16.sp, color = color(mutedColor), singleLine = true)
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
    var showPairing by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it; if (!it) showPairing = false },
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
                    if (menu.canPair || menu.pairing != null) {
                        HorizontalSeparator(Modifier.padding(vertical = 8.dp))
                        Button(
                            onClick = { showPairing = !showPairing; if (showPairing) actions.onRefreshPairing(false) },
                            style = ButtonStyle.Ghost,
                            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (showPairing) "Expanded" else "Collapsed" },
                        ) {
                            Text("Pair a device", fontWeight = FontWeight.Normal, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Icon(if (showPairing) Lucide.ChevronDown else Lucide.ChevronRight, Modifier.size(14.dp))
                        }
                        if (showPairing) menu.pairing?.let { info ->
                            Column(Modifier.padding(12.dp)) {
                                PairingPanel(info, menu.pairingBase, actions.onPairingBase, { actions.onRefreshPairing(true) }, menu.pairingError)
                            }
                        }
                    }
                }
            }
        },
    ) {
        RailItem(menu.active?.label ?: "Server", selected = false, news = false, onClick = { showPairing = false; open = true }) {
            Icon(Lucide.Server, Modifier.size(20.dp), tint = color(mutedColor))
        }
    }
}
