package kinuvo.ui.session

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.ui.agent.*
import kinuvo.ui.settings.*
import kinuvo.app.navigation.*
import kotlin.time.Clock
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.client.ConnectionState
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionStatus
import kinuvo.protocol.usage.Usage
import kinuvo.ui.design.*
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
/**
 * The desktop toolbar: the shell's toggle and, narrow, a new Session. Back and forward are left to the shortcuts and
 * mouse buttons.
 */
/** In place of the composer once a Session can't continue: why, and the way on. */

@Composable
internal fun HeaderControls(wide: Boolean, onToggle: (() -> Unit)?, onNew: (() -> Unit)?) {
    onToggle?.let { if (wide) HeaderButton(Lucide.PanelLeft, "Toggle sidebar ${shortcut(".")}", it) else HeaderButton(Lucide.Menu, "Agents and sessions", it) }
    onNew?.let { HeaderButton(Lucide.SquarePen, "New Session ${shortcut("N")}", it) }
}

internal val ComposerFade = 24.dp

/** Phones: no title, just bubbles floating over the top of the messages, which fade out under them. */
@Composable
internal fun FloatingBar(state: ChatState, usage: Usage?, actions: ChatActions, onToggle: (() -> Unit)?, onBack: (() -> Unit)?, modifier: Modifier) {
    val background = color(backgroundColor)
    Box(modifier.fillMaxWidth().height(FloatingBarHeight).background(Brush.verticalGradient(0.75f to background, 1f to background.copy(alpha = 0f)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.space12, vertical = Spacing.space6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            if (onBack != null) FloatingBubble(Lucide.ArrowLeft, "Back", onBack)
            else onToggle?.let { FloatingBubble(Lucide.Menu, "Agents and sessions", it) }
            val title = (state.sessions.firstOrNull { it.id == state.selected } ?: state.info)?.title
            state.agent?.let { agent ->
                FloatingPill(actions.onSettings, "${agent.name} settings", fill = true, leading = { AgentAvatar(agent, 28.dp) }) {
                    Text(agent.name, style = TextStyles.label, fontWeight = FontWeight.Medium, singleLine = true, overflow = TextOverflow.Ellipsis)
                    Text(title ?: "New Session", style = TextStyles.caption, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.agent == null) Spacer(Modifier.weight(1f))
            PhoneActions(state, usage, actions)
        }
    }
}

internal val FloatingBarHeight = FloatingClearance
internal val VoiceStatusHeight = 44.dp

/** The phone header's trailing bubble: a new Session once this one has started, and the rest under "…". */
@Composable
private fun PhoneActions(state: ChatState, usage: Usage?, actions: ChatActions) {
    var statistics by remember(state.selected) { mutableStateOf(false) }
    val selected = state.selected?.let { id -> state.sessions.firstOrNull { it.id == id } ?: state.info }
    if (state.selected == null && actions.onBrowser == null && state.items.isEmpty()) return
    Bubble {
        if (state.selected != null) BubbleButton(Lucide.SquarePen, "New Session", actions.onNew)
        actions.onBrowser?.let { BubbleButton(Lucide.Globe, "Browser", it) }
        if (state.items.isNotEmpty()) SessionMenu(selected, actions, state, onStatistics = { statistics = true }) { open -> BubbleButton(Lucide.Ellipsis, "More", open) }
    }
    Popover(statistics, { statistics = false }, "Statistics") { ChatStatisticsContent(usage, remember(state.items) { state.items.requestMetrics() }, Modifier.fillMaxWidth()) }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
    Icon(icon, Modifier.size(16.dp))
    Text(text)
}

/**
 * The desktop toolbar: the shell's [controls], then the Session's title and what it's doing; on the right, the Agent (opening
 * its settings) and the Session's actions grouped in one control.
 */
@Composable
internal fun TopBar(state: ChatState, usage: Usage?, actions: ChatActions, compact: Boolean, controls: @Composable () -> Unit) {
    TitleBarRow {
        controls()
        val selected = state.sessions.firstOrNull { it.id == state.selected } ?: state.info
        Row(Modifier.weight(1f).padding(start = Spacing.space6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Text(
                selected?.title ?: if (state.agent != null) "New Session" else "…",
                Modifier.weight(1f, fill = false),
                color = if (selected == null) color(mutedColor) else Color.Unspecified,
                fontWeight = FontWeight.Medium,
                singleLine = true,
                overflow = TextOverflow.Ellipsis,
            )
            selected?.let { SessionBadge(it, compact) }
            SessionActivity(state, compact)
        }
        state.agent?.let { agent -> AgentChip(agent, compact, actions.onSettings) }
        var statistics by remember(state.selected) { mutableStateOf(false) }
        val session = selected?.takeIf { state.selected != null }
        if (session != null || state.items.isNotEmpty()) ToolbarGroup {
            if (session != null) {
                HeaderButton(Lucide.SquarePen, "New Session ${shortcut("N")}", actions.onNew)
                HeaderButton(if (session.favorite) Lucide.StarOff else Lucide.Star, if (session.favorite) "Remove from favorites" else "Add to favorites", { actions.onFavorite(!session.favorite) })
            }
            if (state.items.isNotEmpty()) SessionMenu(selected, actions, state, onStatistics = { statistics = true }, favorite = false) { open -> HeaderButton(Lucide.Ellipsis, "More", open) }
        }
        Popover(statistics, { statistics = false }, "Statistics") {
            ChatStatisticsContent(usage, remember(state.items) { state.items.requestMetrics() }, Modifier.fillMaxWidth())
        }
        actions.onBrowser?.let { ToolbarGroup { HeaderButton(if (actions.browserOpen) Lucide.PanelRightClose else Lucide.Globe, if (actions.browserOpen) "Hide Browser" else "Browser", it) } }
    }
}

/** What the Session is doing, when it's worth a glance: waiting on you, or working. */
@Composable
private fun SessionActivity(state: ChatState, compact: Boolean) = when {
    state.requests.isNotEmpty() -> Pill(if (compact) "${state.requests.size}" else "Needs you", tone = Tone.Warning, icon = Lucide.ShieldAlert)
    state.running -> if (compact) Spinner(size = 12.dp, tint = color(accentColor)) else Pill("Working", tone = Tone.Accent, loading = true)
    else -> Unit
}

/** The Agent this chat is with; opens its settings beside the chat. */
@Composable
private fun AgentChip(agent: kinuvo.protocol.agent.AgentInfo, compact: Boolean, onClick: () -> Unit) {
    Hint("${agent.name}'s settings", TooltipSide.Bottom) {
        Row(Modifier.titleBarControl().height(32.dp).clip(PillShape).border(1.dp, color(borderColor), PillShape).clickable(onClickLabel = "${agent.name}'s settings", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${agent.name}'s settings" }
            .padding(start = Spacing.space4, end = Spacing.space10),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            AgentAvatar(agent, 22.dp)
            if (!compact) Text(agent.name, style = TextStyles.secondary, singleLine = true, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            Icon(Lucide.PanelRight, Modifier.size(14.dp), tint = color(mutedColor))
        }
    }
}

/** Header buttons that belong together, in one outlined pill. */
@Composable
private fun ToolbarGroup(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(34.dp).clip(PillShape).border(1.dp, color(borderColor), PillShape).padding(horizontal = 1.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Statistics, Favorite, Rename and, for an external Agent's running Session, Close, under [trigger]: a sheet on touch screens, a menu otherwise. */
@Composable
private fun SessionMenu(session: SessionInfo?, actions: ChatActions, chat: ChatState, onStatistics: () -> Unit, favorite: Boolean = true, trigger: @Composable (open: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember(session?.id) { mutableStateOf(false) }
    var closing by remember(session?.id) { mutableStateOf(false) }
    val closable = session != null && chat.external && chat.ended == null && chat.capabilities?.chat?.reasonCode != "archived"
    val items = listOfNotNull(
        Triple(Lucide.ChartBar, "Statistics", onStatistics),
        session?.takeIf { favorite }?.let { Triple(if (it.favorite) Lucide.StarOff else Lucide.Star, if (it.favorite) "Remove from favorites" else "Add to favorites") { actions.onFavorite(!it.favorite) } },
        session?.let { Triple(Lucide.Pencil, "Rename") { renaming = true } },
        Triple(Lucide.CircleStop, "Close Session") { closing = true }.takeIf { closable },
    )
    if (LocalInteractionMode.current == InteractionMode.Touch && LocalPopovers.current != null) {
        trigger { menu = true }
        Popover(menu, { menu = false }, session?.title ?: "Session") {
            RowGroup { items.forEach { (icon, label, action) -> GroupRow(label, { menu = false; action() }, icon = icon, chevron = false) } }
        }
    } else DropdownMenu(
        expanded = menu,
        onExpandedChange = { menu = it },
        panel = {
            // Keeps a gutter where the window edge pushes the panel back in.
            DropdownMenuPanel(Modifier.padding(end = Spacing.space12)) {
                items.forEach { (icon, label, action) -> DropdownMenuItem(onClick = { menu = false; action() }) { MenuItem(icon, label) } }
            }
        },
    ) { trigger { menu = true } }
    if (session != null) Popover(renaming, { renaming = false }, "Rename Session") {
        val title = rememberTextFieldState(session.title)
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun save() { actions.onRename(title.text.toString()); renaming = false }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space16)) {
            Input(title, "Session name", trailing = { if (title.text.isNotEmpty()) FieldButton(Lucide.X, "Clear", { title.clearText() }) }, modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { e -> (e.type == KeyEventType.KeyDown && e.key == Key.Enter).also { if (it) save() } }, capitalize = true)
            Button(::save, Modifier.fillMaxWidth(), enabled = title.text.toString().trim() != session.title, style = ButtonStyle.Primary) { Text("Save") }
        }
    }
    if (closable) Popover(closing, { closing = false }, "Close Session") { CloseSessionConfirm(chat, { closing = false }) { closing = false; actions.onCloseSession() } }
}

/** Asks before closing an external Session, saying what stops and what stays. */
@Composable
internal fun CloseSessionConfirm(chat: ChatState, onCancel: () -> Unit, onClose: () -> Unit) {
    val harness = chat.harness ?: "The harness"
    val host = chat.agent?.host
    val busy = chat.running || chat.phase == ExecutionPhase.Running || chat.phase == ExecutionPhase.RequiresAction
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
        Text(buildString {
            append(if (busy) "$harness is still working. Closing stops its current turn" else "Closing stops $harness's process for this Session")
            host?.let { append(" on $it") }
            append(" and drops any request it's waiting on. The transcript stays here.")
            append(when (chat.resume) {
                true -> " You can resume it later."
                false -> " $harness can't resume Sessions, so this one can't continue after closing."
                null -> ""
            })
        }, style = TextStyles.label)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            Button(onCancel, style = ButtonStyle.Ghost) { Text("Keep open") }
            Button(onClose, style = ButtonStyle.Ghost, contentColor = color(destructiveColor)) { Text(if (busy) "Stop and close" else "Close Session") }
        }
    }
}

/**
 * A composer notice: nothing while connected or briefly down, e.g. back from the background, and a brief "Connected" after a
 * reconnect it showed. While the client retries: "Reconnecting" after [ReconnectingAfter], then
 * "Offline" with the reason, or when it was last [syncedAt], once it's been down for [OfflineAfter]. A version mismatch shows
 * right away, since waiting won't fix it.
 */
@Composable
internal fun connectionNotice(connection: ConnectionState, syncedAt: Long?): Pair<Tone, String>? {
    val connected = connection is ConnectionState.Connected
    var down by remember { mutableStateOf(false) }
    var long by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connection) { if (connection is ConnectionState.Disconnected) reason = connection.reason }
    LaunchedEffect(connected) {
        if (!connected) {
            delay(ReconnectingAfter)
            down = true
            delay(OfflineAfter - ReconnectingAfter)
            long = true
        } else if (down) {
            long = false
            delay(2_000)
            down = false
        }
    }
    // Keeps the last state while the banner slides away.
    var shown by remember { mutableStateOf(Tone.Success to "Connected") }
    if (down) shown = when {
        connected -> Tone.Success to "Connected"
        long -> Tone.Danger to ((syncedAt?.let { "Last synced ${relative(it, Clock.System.now().toEpochMilliseconds())}" } ?: reason)?.let { "Offline · $it. Retrying…" } ?: "Offline · Retrying…")
        else -> Tone.Warning to "Reconnecting…"
    }
    if (connection is ConnectionState.Disconnected && connection.incompatible) return Tone.Danger to "${connection.reason} Retrying…"
    return shown.takeIf { down }
}

private const val ReconnectingAfter = 3_000L
private const val OfflineAfter = 8_000L

/** Marks a Session an Agent started on its own: a Task run or a Routine's. */
@Composable
private fun SessionBadge(session: SessionInfo, compact: Boolean = false) = when {
    session.task != null -> if (compact) Icon(Lucide.SquareKanban, Modifier.size(14.dp), contentDescription = "Task run") else Badge("Task", Lucide.SquareKanban)
    session.routine != null -> if (compact) Icon(Lucide.AlarmClock, Modifier.size(14.dp), contentDescription = "Routine run") else Badge("Routine", Lucide.AlarmClock)
    else -> Unit
}

/** Running: spinner; waiting on an Approval: shield; finished while elsewhere: check. */
@Composable
internal fun SessionStatusIcon(status: SessionStatus, finished: Boolean) = when {
    status == SessionStatus.Waiting -> Icon(Lucide.ShieldAlert, Modifier.size(14.dp), tint = color(warningColor), contentDescription = "Needs approval")
    status == SessionStatus.Running -> Spinner(Modifier.semantics { contentDescription = "Running" }, size = 12.dp, tint = color(accentColor))
    finished -> Icon(Lucide.Check, Modifier.size(14.dp), tint = color(successColor), contentDescription = "Finished")
    else -> Unit
}

/** Follows the bottom while [follow] is set; scrolling up detaches it and shows a button to jump back. */
