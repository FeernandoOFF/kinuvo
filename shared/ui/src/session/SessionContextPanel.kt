package kinuvo.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.composeunstyled.theme.Theme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.computer.ComputersState
import kinuvo.app.computer.ComputersViewModel
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.session.*
import kinuvo.protocol.*
import kinuvo.ui.agent.*
import kinuvo.ui.artifacts.HtmlView
import kinuvo.ui.computer.ComputerControls
import kinuvo.ui.computer.ComputerDisplay
import kinuvo.ui.design.*
import kinuvo.ui.routine.describe
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.time.Clock
import kotlinx.coroutines.delay

@Composable
fun SessionContextPanel(
    server: ConnectedServer,
    agentId: String?,
    sessionId: String?,
    roomSessionId: String? = null,
    memberAgentIds: List<String> = emptyList(),
    roomId: String? = null,
    onOpenSession: (agentId: String, sessionId: String) -> Unit,
    onAgentSettings: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = remember(agentId, sessionId, roomId, roomSessionId, memberAgentIds) { SessionContextScope(agentId, sessionId, roomId, roomSessionId, memberAgentIds) }
    val lifetime = contextServerLifetime(server)
    var selectedMember by rememberSaveable(lifetime, agentId, sessionId, roomId, roomSessionId) { mutableStateOf(agentId) }
    var artifactId by rememberSaveable(lifetime, agentId, sessionId, roomId, roomSessionId) { mutableStateOf<String?>(null) }
    val model = remember(server, lifetime, context) { SessionContextViewModel(server, context, selectedMember) }
    LaunchedEffect(model) { artifactId?.let(model::openArtifact) }
    val computers = remember(server, contextServerLifetime(server), context) { contextualComputers(server) }
    DisposableEffect(model, computers) { onDispose { model.dispose(); computers?.dispose() } }
    val state by model.state.collectAsState()
    androidx.navigationevent.compose.NavigationBackHandler(
        androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None),
    ) {
        if (state.artifactId != null) { artifactId = null; model.openArtifact(null) } else onClose()
    }
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) { delay(30_000); value = Clock.System.now().toEpochMilliseconds() }
    }
    var fullscreen by remember { mutableStateOf(false) }
    computers?.let { key(it) {
        val computerState by it.state.collectAsState()
        ContextComputerWatch(computerState, context, state.agentId, state.artifactId == null, fullscreen, it::watchActive)
    } }
    SessionContextContent(state, context, model.management, model.serverCapabilities, now,
        SessionContextActions(
            onSelectAgent = { selectedMember = it; model.selectAgent(it) },
            onRoutine = model::updateRoutine, onRuns = model::loadRuns,
            onArtifact = { artifactId = it; model.openArtifact(it) }, onExecution = model::saveExecution),
        onOpenSession, onAgentSettings, onClose,
        computer = { computers?.let { ContextComputer(it, context, state.agentId) { fullscreen = true } } })
    if (fullscreen && computers != null) ComputerTakeover(computers, manageWatch = false) { fullscreen = false }
}

internal class SessionContextActions(
    val onSelectAgent: (String?) -> Unit = {},
    val onRoutine: (String, RoutineSpec) -> Unit = { _, _ -> },
    val onRuns: (String) -> Unit = {},
    val onArtifact: (String?) -> Unit = {},
    val onExecution: (SessionSettings) -> Unit = {},
)

@Composable
internal fun SessionContextContent(
    state: SessionContextState,
    context: SessionContextScope,
    management: Boolean,
    serverCapabilities: ServerCapabilities?,
    now: Long,
    actions: SessionContextActions = SessionContextActions(),
    onOpenSession: (String, String) -> Unit = { _, _ -> },
    onAgentSettings: (String) -> Unit = {},
    onClose: () -> Unit = {},
    computer: @Composable () -> Unit = {},
) {
    var artifactSessionOnly by rememberSaveable(context.agentId, context.sessionId, context.roomId, context.roomSessionId) { mutableStateOf(context.roomSessionId != null) }
    val sessionOnly = artifactSessionOnly || (context.roomSessionId != null && state.agentId == null)
    Column(Modifier.fillMaxSize().background(color(sidebarColor))) {
        TitleBarRow(trailing = false) {
            if (state.artifactId != null) {
                HeaderButton(Lucide.ArrowLeft, "Back to details", { actions.onArtifact(null) })
                Text("Artifact", Modifier.weight(1f).padding(start = 4.dp), fontWeight = FontWeight.Normal)
            } else {
                val details = state.details?.takeIf { it.info.id == state.agentId && context.memberAgentIds.isEmpty() }
                Row(Modifier.weight(1f).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (details != null) AgentAvatar(details.info, 26.dp)
                    Text(details?.info?.name ?: "Details", fontSize = 16.sp, fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                }
            }
            state.agentId?.let { id -> HeaderButton(Lucide.Settings, "Agent settings", { onAgentSettings(id) }) }
            HeaderButton(Lucide.X, "Close details", onClose)
        }
        HorizontalSeparator()
        if (!management) {
            Text("Context requires management access to this Server.", Modifier.padding(20.dp), color = color(mutedColor))
        } else if (state.artifactId != null) {
            ContextArtifact(state, onOpenSession)
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (context.memberAgentIds.isNotEmpty()) Picker(listOf<String?>(null) + context.memberAgentIds, state.agentId, { id ->
                id?.let { member -> state.agents.firstOrNull { it.id == member }?.name ?: member } ?: "All members"
            }, actions.onSelectAgent)
            state.error?.let { ErrorMessage(it) }
            computer()
            if (state.agentId != null) {
                ContextSection("Routines") {
                    val routines = state.routines
                    when {
                        routines == null -> Spinner(size = 16.dp)
                        routines.isEmpty() -> Text("No Routines for this Agent.", fontSize = 13.sp, color = color(mutedColor))
                        else -> routines.forEach { routine -> key(routine.id) {
                            ContextRoutine(routine, state.runs[routine.id], now, actions.onRoutine, actions.onRuns, onOpenSession)
                        } }
                    }
                }
            } else if (context.memberAgentIds.isNotEmpty()) Text("Choose a member to view their Routines and Session execution.", fontSize = 13.sp, color = color(mutedColor))
            ContextSection(if (sessionOnly) "Session outputs" else "Agent Artifacts") {
                if (state.agentId != null) Picker(listOf(false, true), sessionOnly, { if (it) "This Session" else "All Agent Artifacts" }, { artifactSessionOnly = it })
                val all = state.artifacts
                val shown = if (sessionOnly) context.outputs(all.orEmpty(), state.agentId)
                    else all.orEmpty().filter { it.agentId == state.agentId }
                when {
                    all == null -> Spinner(size = 16.dp)
                    shown.isEmpty() -> Text(if (sessionOnly) "No outputs saved from this Session." else "No Artifacts saved by this Agent.", fontSize = 13.sp, color = color(mutedColor))
                    else -> shown.sortedByDescending { it.updated }.forEach { artifact ->
                        ListRow(selected = false, onClick = { actions.onArtifact(artifact.id) }, leading = { Icon(Lucide.FileText, Modifier.size(16.dp)) }) {
                            Column(Modifier.weight(1f)) {
                                Text(artifact.title, singleLine = true, overflow = TextOverflow.Ellipsis)
                                Text("${state.agents.firstOrNull { it.id == artifact.agentId }?.name ?: artifact.agentId} · ${artifact.type}", fontSize = 12.sp, color = color(mutedColor))
                            }
                            Icon(Lucide.ChevronRight, Modifier.size(14.dp))
                        }
                    }
                }
            }
            if (state.agentId != null) ContextSection("Session execution") {
                if (context.sessionId == null && context.roomSessionId == null) Text("Start a Session to choose its execution.", fontSize = 13.sp, color = color(mutedColor))
                else state.details?.let { details -> key(details.info.id) {
                    ContextExecution(state, details.settings, serverCapabilities, actions.onExecution) { onAgentSettings(details.info.id) }
                } }
            }
        }
    }
}

@Composable
private fun ContextSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, color = color(mutedColor))
        Column(Modifier.fillMaxWidth().background(color(panelColor), Theme[shapes][largeShape]).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Closes an overlaid panel on tap: no ripple, the content behind it blurs instead. */
@Composable
internal fun Scrim(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(color(backgroundColor).copy(alpha = 0.35f))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close details", onClick = onDismiss))
}

@Composable
private fun ContextArtifact(state: SessionContextState, onOpenSession: (String, String) -> Unit) {
    val data = state.artifact
    if (data == null) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            when {
                state.artifactMissing -> Text("This Artifact was deleted.", color = color(mutedColor))
                state.error != null -> ErrorMessage(requireNotNull(state.error))
                else -> Spinner()
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(data.info.title, fontWeight = FontWeight.Normal)
            Text("${data.info.type} · Latest saved content", fontSize = 12.sp, color = color(mutedColor))
            if (data.info.roomSessionId == null) data.info.sessionId?.let { id -> Button(onClick = { onOpenSession(data.info.agentId, id) }, style = ButtonStyle.Link) { Text("Open source Session") } }
        }
        when (data.info.type) {
            "html", "svg" -> HtmlView(if (data.info.type == "svg") "<html><body>${data.content}</body></html>" else data.content, Modifier.weight(1f).fillMaxWidth())
            else -> SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (data.info.type == "md") Markdown(data.content) else CodeBlock(data.content, language = data.info.type)
            }
        }
    }
}

@Composable
private fun ContextRoutine(
    routine: Routine,
    runs: List<SessionInfo>?,
    now: Long,
    onSave: (String, RoutineSpec) -> Unit,
    onRuns: (String) -> Unit,
    onOpenSession: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).clickable { expanded = !expanded; if (expanded) onRuns(routine.id) }.padding(vertical = 6.dp)) {
                Text(routine.name, fontSize = 13.sp)
                Text("${routine.trigger.describe()} · ${routine.timeZone}", fontSize = 12.sp, color = color(mutedColor))
                Text(routine.nextRunAt?.takeIf { routine.enabled }?.let { "Next ${relative(it, now)}" } ?: if (routine.enabled) "No next run scheduled" else "Off", fontSize = 12.sp, color = color(mutedColor))
                routine.lastRunAt?.let { at -> Text("Last ${relative(at, now)}${routine.lastStatus?.let { " · ${it.name}" }.orEmpty()}", fontSize = 12.sp, color = color(mutedColor)) }
            }
            Switch(routine.enabled, { onSave(routine.id, RoutineSpec(enabled = it)) })
            HeaderButton(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, "Edit ${routine.name}", { expanded = !expanded; if (expanded) onRuns(routine.id) })
        }
        if (expanded) key(routine.id, routine.trigger, routine.prompt, routine.name, routine.sessionId) {
            val name = rememberTextFieldState(routine.name)
            val prompt = rememberTextFieldState(routine.prompt)
            var kind by remember { mutableStateOf(when (routine.trigger) { is Trigger.Once -> "once"; is Trigger.Every -> "every"; is Trigger.Cron -> "cron" }) }
            val schedule = rememberTextFieldState(when (val trigger = routine.trigger) {
                is Trigger.Every -> "${trigger.minutes}m"
                is Trigger.Cron -> trigger.expr
                is Trigger.Once -> Instant.fromEpochMilliseconds(trigger.at).toLocalDateTime(runCatching { TimeZone.of(routine.timeZone) }.getOrDefault(TimeZone.UTC)).toString()
            })
            val zone = rememberTextFieldState(routine.timeZone)
            Text(if (routine.sessionId == null) "Each run starts a new Session." else "Each run continues the same Session.", fontSize = 12.sp, color = color(mutedColor))
            routine.sessionId?.let { id -> Button(onClick = { onOpenSession(routine.agentId, id) }, style = ButtonStyle.Link) { Text("Open continued Session") } }
            Input(name, "Name")
            TextArea(prompt, if (routine.heartbeat) "Heartbeat checklist" else "Prompt", lines = 3)
            Picker(listOf("every", "cron", "once"), kind, { when (it) { "every" -> "Every"; "cron" -> "Cron"; else -> "Once" } }, { kind = it })
            Input(schedule, when (kind) { "every" -> "30m"; "cron" -> "0 9 * * MON-FRI"; else -> "2026-10-23T09:00" })
            Input(zone, "Time zone")
            Button(onClick = {
                onSave(routine.id, RoutineSpec(name = name.text.toString().trim(), prompt = prompt.text.toString(),
                    every = schedule.text.toString().takeIf { kind == "every" }, cron = schedule.text.toString().takeIf { kind == "cron" },
                    at = schedule.text.toString().takeIf { kind == "once" }, timeZone = zone.text.toString()))
            }, style = ButtonStyle.Outlined) { Text("Save Routine") }
            Text("Recent runs", fontSize = 12.sp, color = color(mutedColor))
            if (runs == null) Spinner(size = 14.dp)
            else if (runs.isEmpty()) Text("No recorded runs yet.", fontSize = 12.sp, color = color(mutedColor))
            else runs.forEach { run -> Button(onClick = { onOpenSession(routine.agentId, run.id) }, style = ButtonStyle.Link) {
                Text(run.title.ifBlank { run.id }, fontSize = 12.sp, singleLine = true, overflow = TextOverflow.Ellipsis)
            } }
            routine.lastError?.let { ErrorMessage(it) }
        }
    }
}

@Composable
private fun ContextExecution(state: SessionContextState, defaults: AgentSettings, server: ServerCapabilities?, onSave: (SessionSettings) -> Unit, onSettings: () -> Unit) {
    val capabilities = state.capabilities
    if (capabilities == null) { Spinner(size = 16.dp); return }
    if (capabilities.effective == null) {
        Text("Update this Server to change Session execution.", fontSize = 13.sp, color = color(mutedColor))
        Button(onClick = onSettings, style = ButtonStyle.Link) { Text("Agent execution settings") }
        return
    }
    val saved = state.settings
    val effective = state.capabilities?.effective
    val active = state.capabilities?.active
    var editing by remember { mutableStateOf(false) }
    var chosen by remember(saved) { mutableStateOf(saved.exec) }
    val choices = listOf<String?>(null) + EXEC.keys.filter {
        (it == "none" || server.offers(Integration.Exec, it)) && (it != "ssh" || saved.ssh != null || defaults.ssh != null)
    }
    val pending = active != null && effective != null && (active.exec != effective.exec || active.workspace != effective.workspace || active.container != effective.container || active.ssh != effective.ssh)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(EXEC[effective?.exec] ?: effective?.exec ?: "Agent default", fontSize = 13.sp)
            Text(if (saved.exec == null && saved.workspace == null && saved.container == null && saved.ssh == null) "Agent default" else "Session override", fontSize = 12.sp, color = color(mutedColor))
        }
        Button(onClick = { chosen = saved.exec; editing = !editing }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text(if (editing) "Cancel" else "Edit") }
    }
    if (active != null) Text("Current run: ${EXEC[active.exec] ?: active.exec}" + if (pending) "\nNext run: ${EXEC[effective?.exec] ?: effective?.exec}" else "", fontSize = 12.sp, color = color(mutedColor))
    if (state.saving) Spinner(size = 14.dp)
    if (editing) key(saved) {
        Text("Shell and files. Changes apply to the next run; files stay on their current backend. Computer control is separate.", fontSize = 12.sp, color = color(mutedColor))
        Picker((choices + saved.exec).distinct(), chosen, { it?.let { EXEC[it] ?: it } ?: "Follow Agent default" }, { chosen = it })
        val workspace = rememberTextFieldState((saved.workspace ?: defaults.workspace).joinToString("\n"))
        val container = saved.container ?: defaults.container
        val image = rememberTextFieldState(container.image)
        var network by remember { mutableStateOf(container.network) }
        val ssh = saved.ssh ?: defaults.ssh
        val host = rememberTextFieldState(ssh?.host.orEmpty())
        val user = rememberTextFieldState(ssh?.user.orEmpty())
        val port = rememberTextFieldState(ssh?.port?.toString().orEmpty())
        val keyFile = rememberTextFieldState(ssh?.identityFile.orEmpty())
        var error by remember { mutableStateOf<String?>(null) }
        val backend = chosen ?: defaults.exec
        if (chosen != saved.exec) Text("Save to apply this execution choice.", fontSize = 12.sp, color = color(mutedColor))
        if (backend != "none") {
            Text("Workspace", fontSize = 12.sp, color = color(mutedColor))
            TextArea(workspace, "One folder per line", lines = 2)
        }
        if (backend == "container") {
            Input(image, "Container image")
            Checkbox(network, { network = it }) { Text("Network access") }
        }
        if (backend == "ssh") {
            Input(host, "SSH host")
            Input(user, "User (optional)")
            Input(port, "Port (optional)")
            Input(keyFile, "Key file on the Server (optional)")
        }
        error?.let { ErrorMessage(it) }
        Button(onClick = {
            val rawPort = port.text.toString().trim()
            val settings = saved.copy(exec = chosen,
                workspace = if (backend == "none") saved.workspace else workspace.text.toString().lines().map(String::trim).filter(String::isNotEmpty),
                container = if (backend == "container") ContainerSettings(image.text.toString(), network) else saved.container,
                ssh = if (backend == "ssh") SshSettings(host.text.toString(), user.text.toString().ifBlank { null }, rawPort.toIntOrNull(), keyFile.text.toString().ifBlank { null }) else saved.ssh)
            error = runCatching {
                require(backend != "ssh" || rawPort.isEmpty() || rawPort.toIntOrNull() != null) { "Enter a numeric SSH port" }
                validateSessionExecution(settings, defaults)
            }.exceptionOrNull()?.message
            if (error == null) { onSave(if (chosen == null && saved.exec != null) saved.agentExecutionDefault() else settings); editing = false }
        }, style = ButtonStyle.Outlined) { Text("Save execution") }
        if (saved.exec != null || saved.workspace != null || saved.container != null || saved.ssh != null)
            Button(onClick = { onSave(saved.agentExecutionDefault()); chosen = null; editing = false }, style = ButtonStyle.Link) { Text("Use Agent execution default") }
        Button(onClick = onSettings, style = ButtonStyle.Link) { Text("Agent execution settings") }
    }
}

@Composable
internal fun ContextComputerWatch(
    state: ComputersState,
    context: SessionContextScope,
    selectedAgent: String?,
    previewVisible: Boolean,
    fullscreen: Boolean,
    watch: (String?) -> Unit,
) {
    val active = state.computers.orEmpty().filter { context.contains(it, selectedAgent) }
    val preview = active.firstOrNull { it.id == state.selected } ?: active.firstOrNull()
    val target = if (fullscreen) state.computer?.takeIf { it.available }?.id
        else if (previewVisible) preview?.takeIf { it.available && it.control == ComputerControl.Agent }?.id else null
    val currentWatch by rememberUpdatedState(watch)
    LaunchedEffect(target) { currentWatch(target) }
    DisposableEffect(Unit) { onDispose { currentWatch(null) } }
}

@Composable
private fun ContextComputer(computers: ComputersViewModel, context: SessionContextScope, selectedAgent: String?, onTakeover: () -> Unit) {
    val state by computers.state.collectAsState()
    val active = state.computers.orEmpty().filter { context.contains(it, selectedAgent) }
    val computer = active.firstOrNull { it.id == state.selected } ?: active.firstOrNull()
    if (computer == null) {
        state.error?.let { ErrorMessage(it) }
        return
    }
    ContextSection("Computer use") {
        Text("${computer.agentId} · ${computer.name}", fontSize = 13.sp)
        when {
            !computer.available -> Text("This Computer is unavailable.", color = color(mutedColor), fontSize = 12.sp)
            computer.control == ComputerControl.User -> {
                Pill("You have control", tone = Tone.Warning, icon = Lucide.Hand)
                Button(onClick = { computers.select(computer.id); onTakeover() }, style = ButtonStyle.Outlined) { Text("Return to screen") }
                Button(onClick = { computers.select(computer.id); computers.handBack() }, style = ButtonStyle.Primary) { Text("Hand back") }
            }
            else -> {
                Box(Modifier.fillMaxWidth().height(160.dp).clickable { computers.select(computer.id); computers.takeOver(); onTakeover() }) {
                    ComputerDisplay(state.frame?.takeIf { it.computer.id == computer.id && context.contains(it.computer, selectedAgent) }, false, { _, _ -> }, { _, _, _, _ -> }, Modifier.fillMaxSize())
                }
                Button(onClick = { computers.select(computer.id); computers.takeOver(); onTakeover() }, style = ButtonStyle.Outlined) { Text("Take over") }
            }
        }
        computer.takeoverRequest?.let { Text(it, fontSize = 12.sp, color = color(mutedColor)) }
        state.error?.let { ErrorMessage(it) }
    }
}

/** Metadata remains observed after closing the preview; closing never hands a Computer back. */
@Composable
fun SessionComputerIndicator(server: ConnectedServer, onOpen: () -> Unit = {}) {
    val computers = remember(server, contextServerLifetime(server)) { contextualComputers(server) }
    DisposableEffect(computers) { onDispose { computers?.dispose() } }
    if (computers == null) return
    val state by computers.state.collectAsState()
    val controlled = state.computers.orEmpty().filter { it.control == ComputerControl.User }
    var fullscreen by remember { mutableStateOf(false) }
    if (controlled.isNotEmpty()) Row(Modifier.fillMaxWidth().background(color(sidebarColor)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val computer = controlled.firstOrNull { it.id == state.selected } ?: controlled.first()
        Button(onClick = { computers.select(computer.id); fullscreen = true; onOpen() }, style = ButtonStyle.Link) { Text("You control ${computer.name}", fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        Button(onClick = { computers.select(computer.id); computers.handBack() }, style = ButtonStyle.Outlined) { Text("Hand back", fontSize = 12.sp) }
    }
    if (fullscreen) ComputerTakeover(computers) { fullscreen = false }
}

@Composable
private fun ComputerTakeover(computers: ComputersViewModel, manageWatch: Boolean = true, onClose: () -> Unit) {
    val state by computers.state.collectAsState()
    val computer = state.computer
    var controlled by remember { mutableStateOf(state.control == ComputerControl.User) }
    LaunchedEffect(state.control) {
        if (state.control == ComputerControl.User) controlled = true
        else if (controlled) onClose()
    }
    if (manageWatch) key(computers) { ContextComputerWatch(state, SessionContextScope(), null, false, true, computers::watchActive) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
            TitleBarRow(trailing = false) {
                Text(computer?.name ?: "Computer", Modifier.weight(1f), fontWeight = FontWeight.Normal)
                HeaderButton(Lucide.X, "Back to Session", onClose)
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.control == ComputerControl.User) "You have control. Agents are waiting." else "Live view", Modifier.weight(1f), fontSize = 13.sp)
                if (state.control == ComputerControl.User) Button(onClick = computers::handBack, style = ButtonStyle.Primary) { Text("Hand back") }
            }
            state.error?.let { ErrorMessage(it, Modifier.padding(12.dp)) }
            if (computer?.available == false) Text("This Computer is unavailable.", Modifier.padding(20.dp), color = color(mutedColor))
            else ComputerDisplay(state.frame, state.control == ComputerControl.User, computers::tap, computers::swipe, Modifier.weight(1f).fillMaxWidth().padding(12.dp))
            if (state.control == ComputerControl.User && computer?.available == true) Box(Modifier.padding(12.dp)) { ComputerControls(Platform.fromName(computer.platform), computers::key, computers::type) }
        }
    }
}
