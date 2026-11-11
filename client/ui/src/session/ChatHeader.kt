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
import kinuvo.protocol.ExecutionPhase
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionStatus
import kinuvo.protocol.Usage
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

@Composable
internal fun TopBar(state: ChatState, usage: Usage?, actions: ChatActions, compact: Boolean, controls: @Composable () -> Unit) {
    TitleBarRow {
        controls()
        Spacer(Modifier.width(Spacing.space6))
        val selected = state.sessions.firstOrNull { it.id == state.selected } ?: state.info
        Row(Modifier.weight(1f).padding(start = Spacing.space4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            state.agent?.let { agent ->
                Hint("${agent.name}'s settings", TooltipSide.Bottom) {
                    Row(Modifier.titleBarControl().clip(PillShape).clickable(onClickLabel = "${agent.name}'s settings", onClick = actions.onSettings)
                        .semantics(mergeDescendants = true) { contentDescription = "${agent.name}'s settings" }
                        .background(color(panelColor)).padding(start = Spacing.space4, end = if (compact) Spacing.space4 else Spacing.space10, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                        AgentAvatar(agent, 20.dp)
                        if (!compact) Text(agent.name, style = TextStyles.secondary, singleLine = true)
                    }
                }
                if (!compact) Icon(Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
            }
            Text(
                selected?.title ?: if (state.agent != null) "New Session" else "…",
                Modifier.weight(1f, fill = false),
                color = if (selected == null) color(mutedColor) else Color.Unspecified,
                fontWeight = FontWeight.Normal,
                singleLine = true,
                overflow = TextOverflow.Ellipsis,
            )
            selected?.let { SessionBadge(it, compact) }
        }
        var statistics by remember(state.selected) { mutableStateOf(false) }
        if (state.items.isNotEmpty()) SessionMenu(selected, actions, state, onStatistics = { statistics = true }) { open -> HeaderButton(Lucide.Ellipsis, "More", open) }
        Popover(statistics, { statistics = false }, "Statistics") {
            ChatStatisticsContent(usage, remember(state.items) { state.items.requestMetrics() }, Modifier.fillMaxWidth())
        }
        actions.onBrowser?.let { HeaderButton(if (actions.browserOpen) Lucide.PanelRightClose else Lucide.Globe, if (actions.browserOpen) "Hide Browser" else "Browser", it) }
    }
}

/** Statistics, Favorite, Rename and, for an external Agent's running Session, Close, under [trigger]: a sheet on touch screens, a menu otherwise. */
@Composable
private fun SessionMenu(session: SessionInfo?, actions: ChatActions, chat: ChatState, onStatistics: () -> Unit, trigger: @Composable (open: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember(session?.id) { mutableStateOf(false) }
    var closing by remember(session?.id) { mutableStateOf(false) }
    val closable = session != null && chat.external && chat.ended == null && chat.capabilities?.chat?.reasonCode != "archived"
    val items = listOfNotNull(
        Triple(Lucide.ChartBar, "Statistics", onStatistics),
        session?.let { Triple(if (it.favorite) Lucide.StarOff else Lucide.Star, if (it.favorite) "Remove from favorites" else "Add to favorites") { actions.onFavorite(!it.favorite) } },
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
 * "Offline" with the reason, or when it was last [syncedAt], once it's been down for [OfflineAfter].
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
