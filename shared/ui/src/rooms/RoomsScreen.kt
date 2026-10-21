package kinuvo.ui.rooms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.ui.components.*
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.ArrowLeft
import kinuvo.app.rooms.*
import kinuvo.app.navigation.Route
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kinuvo.ui.agent.TextArea
import kinuvo.ui.session.*

@Composable
fun RoomsScreen(agents: List<AgentInfo>, vm: RoomsViewModel, onOpen: (Route.Chat) -> Unit, onMenu: () -> Unit, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsState()
    RoomsContent(agents, state, RoomsActions(
        onMenu = onMenu, onBack = onBack, creating = vm::creating, refresh = { vm.refresh() }, select = vm::select, name = vm::name, member = vm::member,
        coordinator = vm::coordinator, create = { vm.create() }, newSession = { vm.newSession() }, open = vm::open,
        approve = vm::approve, answer = vm::answer, recipient = vm::recipient, text = vm::text,
        send = { vm.send() }, stop = { vm.stop() },
    ), onOpen)
}

@Composable
fun RoomsContent(agents: List<AgentInfo>, state: RoomsState, vm: RoomsActions, onOpen: (Route.Chat) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.onMenu?.let { HeaderButton(Lucide.Menu, "Agents and sessions", it) }
            vm.onBack?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Text("Rooms")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.creating(true) }) { Text("New Room") }
            Button(onClick = { vm.refresh() }, style = ButtonStyle.Ghost) { Text("Refresh") }
            state.rooms.forEach { room ->
                Button(onClick = { if (room.direct) onOpen(Route.Chat(room.coordinator)) else vm.select(room) }, style = if (state.selected?.id == room.id) ButtonStyle.Primary else ButtonStyle.Ghost) {
                    Text(room.name + if (room.direct) " · Direct" else "")
                }
            }
        }
        if (state.creating) {
            val name = rememberTextFieldState(state.name)
            LaunchedEffect(name) { snapshotFlow { name.text.toString() }.collect { vm.name(it) } }
            Input(name, "Room name")
            Text("Members · choose 2 to 8")
            agents.forEach { agent -> Checkbox(agent.id in state.members, { vm.member(agent.id) }) { Text(agent.name) } }
            Text("Coordinator")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                agents.filter { it.id in state.members }.forEach { agent ->
                    Button(onClick = { vm.coordinator(agent.id) }, style = if (state.coordinator == agent.id) ButtonStyle.Primary else ButtonStyle.Ghost) { Text(agent.name) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.create() }) { Text("Create Room") }
                Button(onClick = { vm.creating(false) }, style = ButtonStyle.Ghost) { Text("Cancel") }
            }
        }
        state.selected?.let { room ->
            Text("${room.name} · Coordinator: ${agents.firstOrNull { it.id == room.coordinator }?.name ?: room.coordinator}")
            Text("Members: " + room.members.joinToString { id -> agents.firstOrNull { it.id == id }?.name ?: "$id (deleted)" })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.newSession() }) { Text("New Session") }
                state.sessions.forEachIndexed { index, session ->
                    Button(onClick = { vm.open(session) }, style = ButtonStyle.Ghost) { Text(session.messages.firstOrNull()?.text?.take(32) ?: "Session ${index + 1}") }
                }
            }
            state.view?.let { view ->
                view.session.messages.forEach { message ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(message.senderName + if (message.recipients.isEmpty()) "" else " to " + message.recipients.joinToString { id -> agents.firstOrNull { it.id == id }?.name ?: id })
                        Text(message.text)
                    }
                }
                view.progress?.let { progress ->
                    Text("${agents.firstOrNull { it.id == progress.agentId }?.name ?: progress.agentId} · ${if (progress.live.approval != null || progress.live.questions != null) "Waiting for you" else "Working"}")
                    if (progress.live.text.isNotBlank()) Text(progress.live.text)
                    progress.live.approval?.let { approval ->
                        key(approval.approvalId) {
                            ApprovalCard(kinuvo.app.session.ChatItem.ToolCall(approval.approvalId, approval.approvalId, approval.toolCallName, approval.args, identity = approval.identity, approval = approval), vm.approve)
                        }
                    }
                    progress.live.questions?.let { asked ->
                        var drafts by remember(asked.questionsId) { mutableStateOf<Map<String, Map<String, List<String>>>>(emptyMap()) }
                        QuestionsCard(asked, drafts, ChatActions(
                            onEditAnswer = { form, question, answer -> drafts = drafts + (form to (drafts[form].orEmpty() + (question to answer))) },
                            onAnswer = { vm.answer(asked.questionsId, it) },
                        ))
                    }
                }
                view.error?.let { Text(it) }
                Text("Address members, or leave empty for the coordinator")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    room.members.forEach { id -> Checkbox(id in state.recipients, { vm.recipient(id) }) { Text("@" + (agents.firstOrNull { it.id == id }?.name ?: id)) } }
                }
                val text = rememberTextFieldState(state.text)
                LaunchedEffect(state.text) { if (text.text.toString() != state.text) text.setTextAndPlaceCursorAtEnd(state.text) }
                LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { vm.text(it) } }
                TextArea(text, "Message this Room", lines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.send() }, enabled = !view.running && !state.submitting && state.text.isNotBlank()) { Text("Send") }
                    if (view.running) Button(onClick = { vm.stop() }, style = ButtonStyle.Ghost) { Text("Stop") }
                }
            }
        }
        state.error?.let { Text(it) }
        Text("Room messages are saved. After a Server restart, interrupted work is not resumed automatically; send a new message to continue.")
    }
}

class RoomsActions(
    val onMenu: (() -> Unit)? = null, val onBack: (() -> Unit)? = null,
    val creating: (Boolean) -> Unit = {}, val refresh: () -> Unit = {}, val select: (Room) -> Unit = {},
    val name: (String) -> Unit = {}, val member: (String) -> Unit = {}, val coordinator: (String) -> Unit = {},
    val create: () -> Unit = {}, val newSession: () -> Unit = {}, val open: (RoomSession) -> Unit = {},
    val approve: (String, ApprovalDecision) -> Unit = { _, _ -> },
    val answer: (String, Map<String, List<String>>?) -> Unit = { _, _ -> },
    val recipient: (String) -> Unit = {}, val text: (String) -> Unit = {}, val send: () -> Unit = {}, val stop: () -> Unit = {},
)
