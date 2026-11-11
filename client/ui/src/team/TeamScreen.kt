package kinuvo.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.app.team.TeamViewModel
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.team.Team
import kinuvo.protocol.team.TeamEntry
import kinuvo.protocol.team.TeamEntryKind
import kinuvo.protocol.team.TeamInteraction
import kinuvo.protocol.team.TeamSpec
import kinuvo.ui.agent.AgentAvatar
import kinuvo.ui.design.*
import kinuvo.ui.session.ContentWidth
import kinuvo.ui.navigation.PageAction
import kinuvo.ui.navigation.PageHeader
import kinuvo.ui.session.ChangePreviewView
import kotlinx.coroutines.flow.distinctUntilChanged

/** A Team's shared log with its members' replies and handoffs, what its members wait on, and a composer that @mentions them. */
@Composable
fun TeamScreen(model: TeamViewModel, agents: List<AgentInfo>, onOpenSession: (AgentId, SessionId) -> Unit, onBack: (() -> Unit)? = null) {
    val state by model.state.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    val team = state.team
    val draft = rememberTextFieldState(state.draft)
    LaunchedEffect(draft) { snapshotFlow { draft.text.toString() }.distinctUntilChanged().collect(model::draft) }
    // The ViewModel empties its draft once a message is sent.
    LaunchedEffect(state.draft) { if (state.draft.isEmpty() && draft.text.isNotEmpty()) draft.clearText() }
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        PageHeader(Lucide.Users, team?.name ?: "Team", team?.let { t -> t.members.joinToString { name(it.agentId, agents) } }, actions = listOfNotNull(
            onBack?.let { PageAction(Lucide.ArrowLeft, "Back", it) },
            PageAction(if (editing) Lucide.X else Lucide.Settings2, if (editing) "Close settings" else "Team settings") { editing = !editing },
        ))
        if (team == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                state.error?.let { Text(it, color = color(destructiveColor)) } ?: Spinner()
            }
            return@Column
        }
        if (editing) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                TeamEditor(agents, team, Modifier.widthIn(max = ContentWidth).padding(Spacing.space16)) { spec -> model.update(spec); editing = false }
            }
            return@Column
        }
        TeamLog(team, state.runningMember, agents, Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(Spacing.space12), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            state.pending.forEach { pending ->
                key(pending.interaction.id.value) {
                    MemberRequest(pending, agents, Modifier.widthIn(max = ContentWidth), onOpen = { onOpenSession(pending.agentId, pending.sessionId) }) { model.respond(pending.interaction.id, it) }
                }
            }
            state.error?.let { Text(it, Modifier.widthIn(max = ContentWidth).fillMaxWidth(), style = TextStyles.secondary, color = color(destructiveColor)) }
            TeamComposer(draft, team, agents, state.runningMember != null, state.sending, Modifier.widthIn(max = ContentWidth), onMention = { id -> draft.edit { append("@${id.value} ") } },
                onSend = model::send, onStop = model::stop)
        }
    }
}

@Composable
private fun TeamLog(team: Team, running: AgentId?, agents: List<AgentInfo>, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(team.log.size, running) { if (team.log.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
    LazyColumn(modifier.fillMaxWidth(), list, contentPadding = PaddingValues(Spacing.space16), verticalArrangement = Arrangement.spacedBy(Spacing.space14), horizontalAlignment = Alignment.CenterHorizontally) {
        if (team.log.isEmpty()) item(key = "empty") {
            Text("Write to the Team. @mention members to choose who answers; ${name(team.defaultMember, agents)} answers otherwise.",
                Modifier.widthIn(max = ContentWidth).padding(top = Spacing.space20), style = TextStyles.secondary, color = color(mutedColor))
        }
        items(team.log, key = { it.sequence }) { entry -> Box(Modifier.widthIn(max = ContentWidth).fillMaxWidth()) { TeamEntryView(entry, agents) } }
        if (running != null) item(key = "running") {
            Row(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                AgentAvatar(agents.firstOrNull { it.id == running }, 24.dp, state = kinuvo.app.agent.AvatarState.Working)
                Text("${name(running, agents)} is working…", style = TextStyles.secondary, color = color(mutedColor))
            }
        }
    }
}

@Composable
private fun TeamEntryView(entry: TeamEntry, agents: List<AgentInfo>) {
    when (entry.kind) {
        TeamEntryKind.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(Radius.large)).background(color(selectedControlColor)).padding(horizontal = Spacing.space14, vertical = Spacing.space10)) {
                Text(entry.text)
            }
        }
        TeamEntryKind.Reply -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space10)) {
            AgentAvatar(agents.firstOrNull { it.id == entry.agentId }, 28.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
                Text(name(entry.agentId, agents), style = TextStyles.secondary, fontWeight = FontWeight.Medium)
                Markdown(entry.text)
            }
        }
        TeamEntryKind.Handoff -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.menu)).background(color(subtleColor)).padding(Spacing.space10),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Icon(Lucide.ArrowRightLeft, Modifier.size(14.dp), tint = color(mutedColor))
            Column(Modifier.weight(1f)) {
                Text("${name(entry.agentId, agents)} handed over to ${name(entry.target, agents)}", style = TextStyles.caption, fontWeight = FontWeight.Medium, singleLine = true, overflow = TextOverflow.Ellipsis)
                if (entry.text.isNotBlank()) Text(entry.text, style = TextStyles.caption, color = color(mutedColor), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        TeamEntryKind.Error -> Panel(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Icon(Lucide.CircleX, Modifier.size(15.dp), tint = color(destructiveColor))
                Text(listOfNotNull(entry.agentId?.let { name(it, agents) }, entry.text).joinToString(": "), style = TextStyles.secondary, color = color(destructiveColor))
            }
        }
    }
}

/** A member's Approval answered here; a question form opens in the member's own Session. */
@Composable
private fun MemberRequest(pending: TeamInteraction, agents: List<AgentInfo>, modifier: Modifier, onOpen: () -> Unit, onAnswer: (InteractionAnswer) -> Unit) {
    val shape = Theme[shapes][mediumShape]
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, color(warningColor).copy(alpha = 0.6f), shape).background(color(panelColor)).padding(Spacing.space12),
        verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
        val interaction = pending.interaction
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            AgentAvatar(agents.firstOrNull { it.id == pending.agentId }, 24.dp)
            Column(Modifier.weight(1f)) {
                Text(when (interaction) {
                    is PendingInteraction.Approval -> "Allow ${interaction.identity?.localId ?: interaction.toolCallName}?"
                    is PendingInteraction.Questions -> interaction.title ?: "Questions for you"
                    else -> "Needs you"
                }, singleLine = true, overflow = TextOverflow.Ellipsis)
                Text("From ${name(pending.agentId, agents)}", style = TextStyles.caption, color = color(mutedColor), singleLine = true)
            }
            if (interaction is PendingInteraction.Approval) Pill("${interaction.risk.name} risk", tone = Tone.Warning)
        }
        if (interaction is PendingInteraction.Approval) {
            interaction.preview?.let { ChangePreviewView(it) } ?: CodeBlock(interaction.args, language = "Arguments", maxLines = 6)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Button(onClick = { onAnswer(InteractionAnswer.Approval(ApprovalDecision.AllowOnce)) }, modifier = Modifier.weight(1f), style = ButtonStyle.Primary) { Text("Allow once") }
                Button(onClick = { onAnswer(InteractionAnswer.Approval(ApprovalDecision.Deny(""))) }, modifier = Modifier.weight(1f), style = ButtonStyle.Outlined, contentColor = color(destructiveColor)) { Text("Deny") }
            }
        } else Button(onClick = onOpen, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Outlined) { Text("Answer in ${name(pending.agentId, agents)}'s Session") }
    }
}

@Composable
private fun TeamComposer(draft: TextFieldState, team: Team, agents: List<AgentInfo>, running: Boolean, sending: Boolean, modifier: Modifier, onMention: (AgentId) -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    val focus = remember { FocusRequester() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.space6), verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            team.members.forEach { member ->
                Button(onClick = { onMention(member.agentId); focus.requestFocus() }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) {
                    AgentAvatar(agents.firstOrNull { it.id == member.agentId }, 16.dp)
                    Text("@${name(member.agentId, agents)}", style = TextStyles.caption, singleLine = true)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Input(draft, "Message ${team.name}", Modifier.weight(1f).focusRequester(focus), capitalize = true, lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6))
            if (running) IconButton(onClick = onStop, style = ButtonStyle.Outlined, modifier = Modifier.size(44.dp)) { Icon(Lucide.Square, Modifier.size(16.dp), contentDescription = "Stop") }
            else IconButton(onClick = onSend, enabled = !sending && draft.text.isNotBlank(), style = ButtonStyle.Primary, modifier = Modifier.size(44.dp)) {
                Icon(Lucide.ArrowUp, Modifier.size(18.dp), contentDescription = "Send")
            }
        }
    }
}

/** A Team's name and members, and which member answers messages that mention no one. */
@Composable
fun TeamEditor(agents: List<AgentInfo>, team: Team?, modifier: Modifier = Modifier, onSave: (TeamSpec) -> Unit) {
    val name = rememberTextFieldState(team?.name.orEmpty())
    var members by remember(team) { mutableStateOf(team?.members?.map { it.agentId }.orEmpty()) }
    var lead by remember(team) { mutableStateOf(team?.defaultMember) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
        // A new Team's page header already says so.
        if (team != null) Text("Team settings", style = TextStyles.title, fontWeight = FontWeight.Bold)
        Input(name, "Name", capitalize = true)
        Text("Members", style = TextStyles.caption, color = color(mutedColor))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            agents.forEach { agent ->
                val member = agent.id in members
                ListRow(member, { members = if (member) members - agent.id else members + agent.id; if (member && lead == agent.id) lead = null },
                    leading = { AgentAvatar(agent, 22.dp) }) {
                    Text(agent.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                    if (member) {
                        val isLead = (lead ?: members.firstOrNull()) == agent.id
                        Button(onClick = { lead = agent.id }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = if (isLead) color(accentColor) else color(mutedColor)) {
                            Text(if (isLead) "Answers by default" else "Make default", style = TextStyles.caption, singleLine = true)
                        }
                        Icon(Lucide.Check, Modifier.size(16.dp), tint = color(accentColor), contentDescription = "Member")
                    }
                }
            }
        }
        Button(onClick = { onSave(TeamSpec(name.text.toString().trim(), members, lead?.takeIf { it in members })) },
            enabled = name.text.isNotBlank() && members.isNotEmpty(), modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Primary) {
            Text(if (team == null) "Create Team" else "Save")
        }
    }
}

private fun name(id: AgentId?, agents: List<AgentInfo>) = agents.firstOrNull { it.id == id }?.name ?: id?.value ?: "an Agent"
