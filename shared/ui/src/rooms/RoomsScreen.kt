package kinuvo.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import androidx.compose.ui.graphics.Color
import com.composables.icons.lucide.*
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import kinuvo.app.rooms.*
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.navigation.Route
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.AgentAvatar
import org.koin.compose.koinInject
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

@Composable
fun RoomsScreen(agents: List<AgentInfo>, vm: RoomsViewModel, onOpen: (Route.Chat) -> Unit, onReveal: () -> Unit, onAgentSettings: (String) -> Unit = {}) {
    val state by vm.state.collectAsState()
    val editorRoom = state.selected?.id
    val editorSession = state.view?.session?.id
    val server = koinInject<ConnectedServer>()
    var context by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val desktop = maxWidth >= 1050.dp
        val blur by animateDpAsState(if (context && !desktop) 12.dp else 0.dp)
        Row(Modifier.fillMaxSize().blur(blur)) {
            Box(Modifier.weight(1f)) {
                RoomsContent(agents, state, RoomsActions(
                    onReveal = onReveal, creating = vm::creating, refresh = { vm.refresh() }, select = vm::select, name = vm::name, member = vm::member,
                    create = { vm.create() }, newSession = { vm.newSession() }, open = vm::open,
                    approve = vm::approve, answer = vm::answer,
                    edit = { draft ->
                        vm.edit(draft, editorRoom, editorSession)
                    }, sendDraft = { vm.send(it) }, stop = { vm.stop() }, rename = { vm.rename(it) }, onContext = { context = !context }, contextOpen = context,
                ), onOpen)
            }
            if (context && desktop) Box(Modifier.width(320.dp).fillMaxHeight()) {
                SessionContextPanel(server, null, null, roomSessionId = state.view?.session?.id, roomId = state.selected?.id, memberAgentIds = state.selected?.members.orEmpty(), onOpenSession = { a, s -> onOpen(Route.Chat(a, s)) }, onAgentSettings = onAgentSettings, onClose = { context = false })
            }
        }
        if (context && !desktop) {
            Scrim { context = false }
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().then(if (maxWidth < 600.dp) Modifier.fillMaxWidth() else Modifier.width(360.dp)).background(color(sidebarColor))) {
                SessionContextPanel(server, null, null, roomSessionId = state.view?.session?.id, roomId = state.selected?.id, memberAgentIds = state.selected?.members.orEmpty(), onOpenSession = { a, s -> onOpen(Route.Chat(a, s)) }, onAgentSettings = onAgentSettings, onClose = { context = false })
            }
        }
    }
}

@OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)
@Composable
fun RoomsContent(agents: List<AgentInfo>, state: RoomsState, vm: RoomsActions, onOpen: (Route.Chat) -> Unit, indicator: @Composable () -> Unit = {}) {
    val room = state.selected
    val view = state.view
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        // Like a chat: phones get bubbles floating over the messages, pointers a title bar.
        val floating = maxWidth < 720.dp && LocalInteractionMode.current == InteractionMode.Touch
        val top = if (floating) FloatingBarHeight else 0.dp
        Column(Modifier.fillMaxSize()) {
            if (!floating) {
                RoomHeader(agents, state, vm)
                HorizontalSeparator()
            }
            if (state.creating) {
                Box(Modifier.weight(1f).padding(top = top)) { CreateRoom(agents, state, vm) }
            } else if (room == null) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(top = top).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Rooms", fontSize = 24.sp)
                    Text("A shared space for you and your Agents.", color = color(mutedColor))
                    Button(onClick = { vm.creating(true) }) { Text("New Room") }
                    state.rooms.filterNot { it.direct }.forEach { r -> Button(onClick = { vm.select(r) }, style = ButtonStyle.Ghost) { Text(r.name) } }
                    state.error?.let { Text(it, color = color(destructiveColor)) }
                }
            } else Box(Modifier.weight(1f)) {
                val background = color(backgroundColor)
                val blur = remember(background) { HazeBlurStyle { blurRadius(24.dp); backgroundColor(background) } }
                val haze = rememberHazeState()
                val density = LocalDensity.current
                var composerHeight by remember { mutableStateOf(0.dp) }
                val list = rememberLazyListState()
                LaunchedEffect(view?.session?.id, view?.session?.messages?.size, view?.progress?.live?.text) {
                    if (list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
                }
                LazyColumn(Modifier.fillMaxSize().hazeSource(haze), list, horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(start = 16.dp, top = 20.dp + top, end = 16.dp, bottom = 20.dp + composerHeight), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (view == null || view.session.messages.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(view?.session?.displayTitle() ?: room.name, fontSize = 24.sp)
                            Text("Start a Session with ${room.members.map { id -> agents.firstOrNull { it.id == id }?.name ?: id }.joinToString()}", color = color(mutedColor), fontSize = 13.sp)
                            if (view == null) Button(onClick = vm.newSession) { Text("New Session") }
                        }
                    }
                    items(view?.session?.messages.orEmpty(), key = { it.id }) { message ->
                        val mentioned = remember(message.text, room.members) { com.mohamedrejeb.richeditor.model.RichTextState().apply { setMarkdown(message.text) }.roomDraft(room.members).recipients }
                        val legacyRecipients = message.recipients.filterNot { it in mentioned }
                        Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), horizontalArrangement = if (message.sender == null) Arrangement.End else Arrangement.Start) {
                            if (message.sender == null) Panel(
                                Modifier.widthIn(max = 560.dp), color = color(selectedControlColor), borderColor = Color.Transparent,
                                shape = Theme[shapes][largeShape], contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            ) { RichMarkdown(message.text) }
                            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    agents.firstOrNull { it.id == message.sender }?.let { AgentAvatar(it, 24.dp) }
                                    Text(message.senderName, Modifier.widthIn(max = 180.dp), fontSize = 13.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
                                    if (legacyRecipients.isNotEmpty()) Text(legacyRecipients.joinToString(" ") { id -> "@" + (agents.firstOrNull { it.id == id }?.name ?: id) },
                                        Modifier.weight(1f), fontSize = 12.sp, color = color(accentColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                RichMarkdown(message.text)
                            }
                        }
                    }
                    view?.progress?.let { progress -> item(key = "progress") {
                        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(agents.firstOrNull { it.id == progress.agentId }?.name ?: progress.agentId, color = color(mutedColor), fontSize = 13.sp)
                            if (progress.live.text.isNotBlank()) RichMarkdown(progress.live.text)
                            progress.live.approval?.let { approval -> key(approval.approvalId) {
                                ApprovalCard(kinuvo.app.session.ChatItem.ToolCall(approval.approvalId, approval.approvalId, approval.toolCallName, approval.args, identity = approval.identity, approval = approval), vm.approve)
                            } }
                            progress.live.questions?.let { asked ->
                                var drafts by remember(asked.questionsId) { mutableStateOf<Map<String, Map<String, List<String>>>>(emptyMap()) }
                                QuestionsCard(asked, drafts, ChatActions(onEditAnswer = { form, question, answer -> drafts = drafts + (form to (drafts[form].orEmpty() + (question to answer))) }, onAnswer = { vm.answer(asked.questionsId, it) }))
                            }
                        }
                    } }
                    (state.error ?: view?.error)?.let { error -> item { Text(error, Modifier.widthIn(max = 760.dp).fillMaxWidth(), color = color(destructiveColor), fontSize = 13.sp) } }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                    .padding(horizontal = 16.dp).padding(bottom = if (floating) 16.dp else 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    indicator()
                    if (view != null) key(room.id, view.session.id) {
                        val text = rememberRichTextState()
                        remember(text) { text.setMarkdown(state.text) }
                        var previous by remember { mutableStateOf(state.text) }
                        LaunchedEffect(state.text) {
                            if (text.toMarkdown().trimEnd() == previous.trimEnd() && previous != state.text) text.setMarkdown(state.text)
                            previous = state.text
                        }
                        val completions = remember(room.members, agents) { listOf(roomCompletions(room.members, agents)) }
                        LaunchedEffect(text, room.members) { snapshotFlow { text.annotatedString to text.roomDraft(room.members) }.collect { vm.edit(it.second) } }
                        Composer(view.running, {
                            val draft = text.roomDraft(room.members)
                            vm.sendDraft(draft)
                        }, vm.stop, modifier = Modifier.widthIn(max = 760.dp), placeholder = "Message ${room.name}", state = text,
                            completions = completions, sending = state.submitting, clearOnSend = false,
                            containerColor = color(fieldColor).copy(alpha = 0.7f), backdrop = Modifier.hazeBlur(HazeInput.Sources(haze), blur))
                    }
                }
            }
        }
        if (floating) RoomFloatingBar(agents, state, vm, Modifier.align(Alignment.TopCenter))
    }
}

/** Phones: the chat's floating bubbles, with the Room in place of the Agent. */
@Composable
private fun RoomFloatingBar(agents: List<AgentInfo>, state: RoomsState, actions: RoomsActions, modifier: Modifier) {
    val room = state.selected?.takeIf { !state.creating }
    val session = state.view?.session
    val background = color(backgroundColor)
    Box(modifier.fillMaxWidth().height(FloatingBarHeight).background(Brush.verticalGradient(0.6f to background, 1f to background.copy(alpha = 0f)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.onReveal?.let { Bubble { BubbleButton(Lucide.Menu, "Agents and sessions", it) } }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (room != null) TitlePill(room.name, "Session details", actions.onContext, subtitle = session?.displayTitle()) { MemberStack(agents, room, 26.dp) }
            }
            if (room != null) Bubble {
                if (session != null) BubbleButton(Lucide.SquarePen, "New Session", actions.newSession)
                if (session != null) RenameMenu(session.displayTitle(), actions.rename) { open -> BubbleButton(Lucide.Ellipsis, "More", open) }
            }
        }
    }
}

/** Rename for the open Session, under [trigger]. */
@Composable
private fun RenameMenu(title: String, onRename: (String) -> Unit, trigger: @Composable (open: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    DropdownMenu(expanded = menu, onExpandedChange = { menu = it }, panel = {
        DropdownMenuPanel {
            DropdownMenuItem(onClick = { menu = false; renaming = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Lucide.Pencil, Modifier.size(16.dp)); Text("Rename") }
            }
        }
    }) { trigger { menu = true } }
    Popover(renaming, { renaming = false }, "Rename Session") {
        val field = rememberTextFieldState(title)
        fun save() { onRename(field.text.toString().trim()); renaming = false }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Input(field, "Session name", capitalize = true)
            Button(::save, Modifier.fillMaxWidth(), enabled = field.text.toString().trim().let { it.isNotEmpty() && it != title }) { Text("Save") }
        }
    }
}

@Composable
private fun MemberStack(agents: List<AgentInfo>, room: Room, size: androidx.compose.ui.unit.Dp) {
    Box {
        room.members.take(3).forEachIndexed { i, id ->
            AgentAvatar(agents.firstOrNull { it.id == id }, size, Modifier.padding(start = size * 0.7f * i).border(1.5.dp, color(panelColor), CircleShape))
        }
    }
}

/**
 * One title-bar row: the Room's members and name, which reveal it in the sidebar, then the Session title, which renames it
 * in place. Phones stack the two so the title keeps the width.
 */
@Composable
internal fun RoomHeader(agents: List<AgentInfo>, state: RoomsState, actions: RoomsActions) {
    val room = state.selected?.takeIf { !state.creating }
    val session = state.view?.session
    val title = if (state.creating) "New Room" else session?.displayTitle() ?: if (room != null) "New Session" else "Rooms"
    var renaming by remember(session?.id) { mutableStateOf(false) }
    val rename = { renaming = true }.takeIf { session != null && !state.creating }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        TitleBarRow {
            if (compact) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                room?.let { RoomGroup(agents, it, actions.onReveal, compact = true) }
                SessionTitle(title, renaming, 16.sp, rename, { renaming = false }, actions.rename, Modifier.fillMaxWidth())
            } else {
                room?.let {
                    RoomGroup(agents, it, actions.onReveal, compact = false, named = !renaming)
                    if (!renaming) Text("/", color = color(mutedColor), fontSize = 16.sp)
                }
                SessionTitle(title, renaming, 15.sp, rename, { renaming = false }, actions.rename, Modifier.weight(1f))
            }
            if (room != null) ContextToggle(actions.contextOpen, actions.onContext)
        }
    }
}

@Composable
private fun RoomGroup(agents: List<AgentInfo>, room: Room, onReveal: (() -> Unit)?, compact: Boolean, named: Boolean = true) {
    val size = if (compact) 18.dp else 24.dp
    Row(
        Modifier.height(if (compact) 22.dp else 36.dp).clip(Theme[shapes][mediumShape])
            .then(if (onReveal != null) Modifier.clickable(onClickLabel = "Show in sidebar", role = Role.Button, onClick = onReveal).titleBarControl() else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "Show ${room.name} in sidebar" }
            .padding(start = 4.dp, end = if (named) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        MemberStack(agents, room, size)
        if (named) Text(room.name, color = color(mutedColor), fontSize = if (compact) 13.sp else 15.sp, singleLine = true, overflow = TextOverflow.Ellipsis)
    }
}

/** The Session title; clicking starts an inline rename that Enter or leaving saves and Escape cancels. */
@Composable
private fun SessionTitle(title: String, renaming: Boolean, size: TextUnit, onRename: (() -> Unit)?, onDone: () -> Unit, save: (String) -> Unit, modifier: Modifier) {
    val shape = Theme[shapes][mediumShape]
    if (!renaming) {
        Box(modifier, contentAlignment = Alignment.CenterStart) {
            Tooltip(side = TooltipSide.Bottom, panel = { TooltipPanel { Text(title, Modifier.widthIn(max = 420.dp)) } }) {
                Text(title, (if (onRename != null) Modifier.clip(shape).clickable(onClickLabel = "Rename Session", onClick = onRename)
                    .pointerHoverIcon(PointerIcon.Text).titleBarControl() else Modifier).padding(horizontal = 6.dp, vertical = 4.dp),
                    fontSize = size, singleLine = true, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    val field = rememberTextFieldState(title, TextRange(0, title.length))
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun finish(keep: Boolean) {
        if (done) return
        done = true
        val value = field.text.toString().trim()
        if (keep && value.isNotEmpty() && value != title) save(value)
        onDone()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        field,
        modifier.focusRequester(focus).titleBarControl()
            .onFocusChanged { if (focused && !it.isFocused) finish(true); focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) { Key.Escape -> { finish(false); true }; Key.Enter, Key.NumPadEnter -> { finish(true); true }; else -> false }
            }
            .semantics { contentDescription = "Session name" }
            .background(color(panelColor), shape).border(1.dp, color(accentColor), shape).padding(horizontal = 6.dp, vertical = 4.dp),
        textStyle = inputTextStyle().copy(fontSize = size, color = color(onBackgroundColor)),
        cursorBrush = SolidColor(color(accentColor)),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = inputKeyboard(capitalize = true).copy(imeAction = ImeAction.Done),
        onKeyboardAction = { finish(true) },
    )
}

@Composable
private fun ContextToggle(open: Boolean, onClick: () -> Unit) {
    val label = if (open) "Hide Session details" else "Show Session details"
    Tooltip(side = TooltipSide.Bottom, panel = { TooltipPanel { Text(label, fontSize = 12.sp) } }) {
        IconButton(onClick = onClick, style = if (open) ButtonStyle.Secondary else ButtonStyle.Ghost, shape = Theme[shapes][mediumShape],
            contentColor = color(if (open) onBackgroundColor else mutedColor),
            modifier = Modifier.size(if (hasTouchKeyboard) 44.dp else 34.dp).titleBarControl().semantics { stateDescription = if (open) "Shown" else "Hidden" }) {
            Icon(Lucide.PanelRight, Modifier.size(18.dp), contentDescription = label)
        }
    }
}

@Composable
private fun CreateRoom(agents: List<AgentInfo>, state: RoomsState, vm: RoomsActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New Room", fontSize = 24.sp)
        val name = rememberTextFieldState(state.name)
        LaunchedEffect(name) { snapshotFlow { name.text.toString() }.collect(vm.name) }
        Input(name, "Room name")
        Text("Members · choose 2 to 8", color = color(mutedColor))
        agents.forEach { agent -> Checkbox(agent.id in state.members, { vm.member(agent.id) }) { Text(agent.name) } }
        state.error?.let { Text(it, color = color(destructiveColor)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm.create, enabled = state.name.isNotBlank() && state.members.size in 2..8) { Text("Create Room") }
            Button(onClick = { vm.creating(false) }, style = ButtonStyle.Ghost) { Text("Cancel") }
        }
    }
}

class RoomsActions(
    val contextOpen: Boolean = false, val onReveal: (() -> Unit)? = null, val onContext: () -> Unit = {},
    val creating: (Boolean) -> Unit = {}, val refresh: () -> Unit = {}, val select: (Room) -> Unit = {},
    val name: (String) -> Unit = {}, val member: (String) -> Unit = {},
    val create: () -> Unit = {}, val newSession: () -> Unit = {}, val open: (RoomSession) -> Unit = {},
    val approve: (String, ApprovalDecision) -> Unit = { _, _ -> },
    val answer: (String, Map<String, List<String>>?) -> Unit = { _, _ -> },
    val rename: (String) -> Unit = {},
    val edit: (RoomDraft) -> Unit = {}, val sendDraft: (RoomDraft) -> Unit = {},
    val stop: () -> Unit = {},
)
