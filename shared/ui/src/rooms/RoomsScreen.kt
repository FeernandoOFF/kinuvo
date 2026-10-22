package kinuvo.ui.rooms

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.*
import com.composables.ui.theme.*
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

@Composable
fun RoomsScreen(agents: List<AgentInfo>, vm: RoomsViewModel, onOpen: (Route.Chat) -> Unit, onMenu: () -> Unit, onBack: (() -> Unit)? = null, onAgentSettings: (String) -> Unit = {}) {
    val state by vm.state.collectAsState()
    val server = koinInject<ConnectedServer>()
    var context by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val desktop = maxWidth >= 1050.dp
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                RoomsContent(agents, state, RoomsActions(
                    onMenu = onMenu, onBack = onBack, creating = vm::creating, refresh = { vm.refresh() }, select = vm::select, name = vm::name, member = vm::member,
                    create = { vm.create() }, newSession = { vm.newSession() }, open = vm::open,
                    approve = vm::approve, answer = vm::answer, recipient = vm::recipient, text = vm::text,
                    send = { vm.send() }, stop = { vm.stop() }, rename = { vm.rename(it) }, onContext = { context = !context },
                ), onOpen)
            }
            if (context && desktop) Box(Modifier.width(320.dp).fillMaxHeight()) {
                SessionContextPanel(server, null, null, roomSessionId = state.view?.session?.id, roomId = state.selected?.id, memberAgentIds = state.selected?.members.orEmpty(), onOpenSession = { a, s -> onOpen(Route.Chat(a, s)) }, onAgentSettings = onAgentSettings, onClose = { context = false })
            }
        }
        if (context && !desktop) {
            Box(Modifier.fillMaxSize().background(color(backgroundColor).copy(alpha = .65f)).clickable { context = false })
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().then(if (maxWidth < 600.dp) Modifier.fillMaxWidth() else Modifier.width(360.dp)).background(color(sidebarColor))) {
                SessionContextPanel(server, null, null, roomSessionId = state.view?.session?.id, roomId = state.selected?.id, memberAgentIds = state.selected?.members.orEmpty(), onOpenSession = { a, s -> onOpen(Route.Chat(a, s)) }, onAgentSettings = onAgentSettings, onClose = { context = false })
            }
        }
    }
}

@Composable
fun RoomsContent(agents: List<AgentInfo>, state: RoomsState, vm: RoomsActions, onOpen: (Route.Chat) -> Unit, indicator: @Composable () -> Unit = {}) {
    val room = state.selected
    val view = state.view
    var renaming by remember(view?.session?.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TitleBarRow {
            vm.onMenu?.let { HeaderButton(Lucide.Menu, "Agents and sessions", it) }
            vm.onBack?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(room?.name ?: "Rooms", fontWeight = FontWeight.Medium, singleLine = true)
                view?.session?.let { Text(it.displayTitle(), color = color(mutedColor), fontSize = 12.sp, singleLine = true) }
            }
            if (view != null) HeaderButton(Lucide.Pencil, "Rename Session", { renaming = true })
            if (room != null) HeaderButton(Lucide.SquarePen, "New Session", vm.newSession)
            HeaderButton(Lucide.PanelRight, "Session details", vm.onContext)
        }
        HorizontalSeparator()
        if (renaming && view != null) Popover(true, { renaming = false }, "Rename Session") {
            val title = rememberTextFieldState(view.session.displayTitle())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Input(title, "Session name")
                Button(onClick = { vm.rename(title.text.toString()); renaming = false }, enabled = title.text.isNotBlank()) { Text("Save") }
            }
        }
        if (state.creating) {
            CreateRoom(agents, state, vm)
        } else if (room == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Rooms", fontSize = 24.sp)
                Text("A shared space for you and your Agents.", color = color(mutedColor))
                Button(onClick = { vm.creating(true) }) { Text("New Room") }
                state.rooms.filterNot { it.direct }.forEach { r -> Button(onClick = { vm.select(r) }, style = ButtonStyle.Ghost) { Text(r.name) } }
                state.error?.let { Text(it, color = color(destructiveColor)) }
            }
        } else {
            val list = rememberLazyListState()
            LaunchedEffect(view?.session?.id, view?.session?.messages?.size, view?.progress?.live?.text) {
                if (list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (view == null || view.session.messages.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(view?.session?.displayTitle() ?: room.name, fontSize = 24.sp)
                        Text("Start a Session with ${room.members.map { id -> agents.firstOrNull { it.id == id }?.name ?: id }.joinToString()}", color = color(mutedColor), fontSize = 13.sp)
                        if (view == null) Button(onClick = vm.newSession) { Text("New Session") }
                    }
                }
                items(view?.session?.messages.orEmpty(), key = { it.id }) { message ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.sender == null) Alignment.CenterEnd else Alignment.CenterStart) {
                        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                agents.firstOrNull { it.id == message.sender }?.let { AgentAvatar(it, 24.dp) }
                                Text(message.senderName, fontSize = 13.sp, color = color(mutedColor))
                            }
                            if (message.recipients.isNotEmpty()) Text("To " + message.recipients.joinToString { id -> agents.firstOrNull { it.id == id }?.name ?: id }, fontSize = 12.sp, color = color(mutedColor))
                            RichMarkdown(message.text)
                        }
                    }
                }
                view?.progress?.let { progress -> item(key = "progress") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                (state.error ?: view?.error)?.let { error -> item { Text(error, color = color(destructiveColor), fontSize = 13.sp) } }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                indicator()
                if (view != null) key(room.id, view.session.id) {
                    val text = rememberRichTextState()
                    remember(text) { text.setMarkdown(state.text) }
                    var previous by remember { mutableStateOf(state.text) }
                    LaunchedEffect(state.text) {
                        if (text.toMarkdown().trimEnd() == previous.trimEnd() && previous != state.text) text.setMarkdown(state.text)
                        previous = state.text
                    }
                    LaunchedEffect(text) { snapshotFlow { text.annotatedString }.collect { vm.text(text.toMarkdown().trimEnd()) } }
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                        var recipients by remember { mutableStateOf(false) }
                        Button(onClick = { recipients = !recipients }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) {
                            Text(if (state.recipients.isEmpty()) "To Room · @mention to direct" else "To " + state.recipients.joinToString { id -> agents.firstOrNull { it.id == id }?.name ?: id }, fontSize = 12.sp)
                        }
                        if (recipients) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            room.members.forEach { id -> Checkbox(id in state.recipients, { vm.recipient(id) }) { Text("@" + (agents.firstOrNull { it.id == id }?.name ?: id)) } }
                        }
                        Composer(view.running, { vm.text(it); vm.send() }, vm.stop, placeholder = "Message ${room.name}", state = text, sending = state.submitting, clearOnSend = false, hint = "Members can reply or pass")
                    }
                }
            }
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
    val onMenu: (() -> Unit)? = null, val onBack: (() -> Unit)? = null, val onContext: () -> Unit = {},
    val creating: (Boolean) -> Unit = {}, val refresh: () -> Unit = {}, val select: (Room) -> Unit = {},
    val name: (String) -> Unit = {}, val member: (String) -> Unit = {},
    val create: () -> Unit = {}, val newSession: () -> Unit = {}, val open: (RoomSession) -> Unit = {},
    val approve: (String, ApprovalDecision) -> Unit = { _, _ -> },
    val answer: (String, Map<String, List<String>>?) -> Unit = { _, _ -> },
    val rename: (String) -> Unit = {},
    val recipient: (String) -> Unit = {}, val text: (String) -> Unit = {}, val send: () -> Unit = {}, val stop: () -> Unit = {},
)
