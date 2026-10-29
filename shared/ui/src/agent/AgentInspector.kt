package kinuvo.ui.agent

import kinuvo.app.agent.*
import kinuvo.app.navigation.*
import kinuvo.ui.session.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.*
import kinuvo.ui.design.*

/** What the inspector needs from its host: every Agent (for validation) and a way to the Agent's full Settings. */
class InspectorHost(
    val agents: List<AgentInfo>,
    val capabilities: ServerCapabilities?,
    /** The selected Server's machine, as labels name it. */
    val server: String = "this Server",
    /** Opens one of the Agent's Settings sections, after any unsaved edits are settled. */
    val onSection: (agentId: String, section: SettingsSection) -> Unit = { _, _ -> },
    val section: (@Composable () -> Unit)? = null,
    val onSectionBack: (() -> Unit)? = null,
    val onCloseRequest: (() -> Unit)? = null,
)

/** Width of the inspector beside the screens on wide layouts. */
val InspectorWidth = 368.dp

/** Actions shared by the inspector panel, sheet and unsaved-edit guard. */
class InspectorActions(
    val onBack: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onEdit: ((AgentDraft) -> AgentDraft) -> Unit = {},
    val onSave: () -> Unit = {},
    val onPush: (InspectorPage) -> Unit = {},
    val onLoadModels: (Boolean) -> Unit = {},
    val onResolve: (GuardChoice) -> Unit = {},
    /** Checks an external Agent's harness again. */
    val onCheck: () -> Unit = {},
)

private fun AgentInspectorViewModel.actions(host: InspectorHost) = InspectorActions(
    onBack = { back() }, onClose = { host.onCloseRequest?.invoke() ?: close() }, onEdit = ::edit, onSave = { save(host.agents) },
    onPush = ::push, onLoadModels = { loadModels(it) }, onResolve = { resolve(it, host.agents) }, onCheck = ::check,
)

@Composable
fun AgentInspector(vm: AgentInspectorViewModel, host: InspectorHost, sheet: Boolean, modifier: Modifier = Modifier, showGuard: Boolean = true) {
    val state by vm.state.collectAsState()
    AgentInspectorContent(state, host, vm.actions(host), sheet, modifier, showGuard)
}

/** State and callbacks also let previews render the real panel and sheet without a Server. */
@Composable
fun AgentInspectorContent(state: InspectorState, host: InspectorHost, actions: InspectorActions, sheet: Boolean, modifier: Modifier = Modifier, showGuard: Boolean = true) {
    var shown by remember { mutableStateOf(state) }
    if (state.open) shown = state
    val savedPages = key(state.agentId) { rememberSaveableStateHolder() }
    val pageContent: @Composable () -> Unit = {
        if (host.section != null) host.section.invoke()
        else savedPages.SaveableStateProvider(shown.page.name) {
            Column(Modifier.fillMaxSize()) {
                InspectorHeader(shown, host, actions)
                Box(Modifier.weight(1f)) { InspectorBody(shown, host, actions) }
            }
        }
    }
    val panelSemantics = if (state.guard != null) Modifier.clearAndSetSemantics {} else Modifier
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = state.open && state.guard == null, onBackCompleted = host.onSectionBack ?: actions.onBack)
    if (sheet) BottomSheet(
        state.open, onDismissRequest = actions.onClose,
        header = {},
        modifier = panelSemantics,
    ) { pageContent() }
    else if (state.open) Column(modifier.then(panelSemantics).width(InspectorWidth).fillMaxHeight().background(color(sidebarColor)).semantics { paneTitle = "Agent settings" }) {
        pageContent()
    }
    if (showGuard) InspectorGuard(state, host, actions)
}

/** The host places the guard above its whole layout, including the rail and transcript. */
@Composable
fun AgentInspectorGuard(vm: AgentInspectorViewModel, host: InspectorHost) {
    val state by vm.state.collectAsState()
    InspectorGuard(state, host, vm.actions(host))
}

@Composable
private fun InspectorHeader(state: InspectorState, host: InspectorHost, actions: InspectorActions) {
    val agent = host.agents.firstOrNull { it.id == state.agentId }
    SettingsPageHeader(
        if (state.pages.isEmpty()) agent?.name ?: "…" else state.page.title(),
        back = actions.onBack.takeIf { state.pages.isNotEmpty() },
        subtitle = if (state.pages.isEmpty()) "Agent settings · every Session" else "${agent?.name ?: "Agent"} · every Session",
    ) {
        if (state.dirty) HeaderButton(Lucide.Check, "Save", actions.onSave, enabled = !state.saving && state.draft?.problems(state.agentId.orEmpty(), host.agents)?.isEmpty() == true)
    }
}

@Composable
private fun InspectorBody(state: InspectorState, host: InspectorHost, actions: InspectorActions) {
    val draft = state.draft
    val details = state.details
    if (draft == null || details == null) return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.errors.forEach { ErrorMessage(it) }
        when (state.page) {
            InspectorPage.Agent -> if (details.info.external) ExternalAgentPage(state, draft, details, host, actions) else AgentPage(state, draft, details, host, actions)
            InspectorPage.Model -> ModelPage(state, draft, actions)
            InspectorPage.Approvals -> APPROVALS.forEach { (key, hint) ->
                ListRow(draft.approvals == key, { actions.onEdit { it.copy(approvals = key) }; actions.onBack() }) {
                    Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                        Text(key.replaceFirstChar { it.uppercase() })
                        Text(hint, fontSize = 12.sp, color = color(mutedColor))
                    }
                    if (draft.approvals == key) Icon(Lucide.Check, Modifier.size(16.dp))
                }
            }
            InspectorPage.UserNotes -> Text(details.userNotes.ifBlank { "${details.info.name} hasn't written any User Notes yet." }, fontSize = 14.sp,
                color = color(if (details.userNotes.isBlank()) mutedColor else onBackgroundColor))
        }
    }
}

@Composable
private fun AgentPage(state: InspectorState, draft: AgentDraft, details: AgentDetails, host: InspectorHost, actions: InspectorActions) {
    val agent = host.agents.firstOrNull { it.id == details.info.id } ?: details.info
    val agentId = details.info.id
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AgentAvatar(agent, 64.dp) }
    Group("Identity") {
        Label("Name")
        Input(draftField(agentId, "name", draft.name) { v -> actions.onEdit { it.copy(name = v) } }, "Name", capitalize = true)
        draft.problems(agentId, host.agents).filter { it.contains("Name", true) || it.contains("called") }.forEach {
            Text(it, fontSize = 12.sp, color = color(destructiveColor))
        }
        Label("Personality (IDENTITY.md)")
        TextArea(draftField(agentId, "identity", draft.identity) { v -> actions.onEdit { it.copy(identity = v) } }, lines = 3)
    }
    Group("Soul", "Principles, tone and rules") {
        TextArea(draftField(agentId, "soul", draft.soul) { v -> actions.onEdit { it.copy(soul = v) } }, lines = 5)
    }
    Group("Model") {
        val selected = state.models.model(draft.provider, draft.model)
        val provider = state.models.providers.firstOrNull { it.id == draft.provider }?.name ?: draft.provider
        NavRow(Lucide.Cpu, "Model", "${selected?.name ?: draft.model} · $provider") { actions.onPush(InspectorPage.Model) }
        val levels = selected?.reasoning ?: (REASONING + draft.reasoning).distinct()
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Brain, Modifier.size(16.dp), tint = color(mutedColor))
            Text("Reasoning", Modifier.weight(1f).padding(start = 10.dp))
            if (levels.isEmpty()) Text("Not supported", color = color(mutedColor), fontSize = 13.sp)
            else Picker(levels, draft.reasoning, { it.replaceFirstChar { c -> c.uppercase() } }, { level -> actions.onEdit { it.copy(reasoning = level) } })
        }
    }
    Group("Rules") {
        NavRow(Lucide.ShieldCheck, "Risk tolerance", "${draft.approvals.replaceFirstChar { it.uppercase() }} · ${APPROVALS[draft.approvals].orEmpty()}") { actions.onPush(InspectorPage.Approvals) }
    }
    Group("Knows") {
        NavRow(Lucide.NotebookPen, "User Notes", if (details.userNotes.isBlank()) "None yet" else "${details.userNotes.lines().count { it.isNotBlank() }} lines") { actions.onPush(InspectorPage.UserNotes) }
        if (host.capabilities.allows(SettingsSection.Skills)) NavRow(Lucide.BookOpen, "Skills", null) { host.onSection(agentId, SettingsSection.Skills) }
    }
    if (host.capabilities.allows(SettingsSection.Workspace)) Group("Does") {
        NavRow(Lucide.FolderOpen, "Workspace", null) { host.onSection(agentId, SettingsSection.Workspace) }
    }
    Text("These are ${agent.name}'s defaults for every Session. A Session's own model, reasoning and Risk tolerance are set from its composer.",
        fontSize = 12.sp, color = color(mutedColor))
}

/** An external Agent: its name and where it runs; its harness owns everything else, so Kinuvo's own settings are absent. */
@Composable
private fun ExternalAgentPage(state: InspectorState, draft: AgentDraft, details: AgentDetails, host: InspectorHost, actions: InspectorActions) {
    val agent = host.agents.firstOrNull { it.id == details.info.id } ?: details.info
    val agentId = details.info.id
    val connections = state.connection?.let(::listOf)
    val harness = harnessName(agent, connections)
    val machine = agent.host ?: host.server
    val problems = draft.problems(agentId, host.agents)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AgentAvatar(agent, 64.dp) }
    Group("Identity") {
        Label("Name")
        Input(draftField(agentId, "name", draft.name) { v -> actions.onEdit { it.copy(name = v) } }, "Name", capitalize = true)
        problems.filter { it.contains("Name", true) || it.contains("called") }.forEach { Text(it, fontSize = 12.sp, color = color(destructiveColor)) }
    }
    Group("Runs on") {
        NavRow(if (agent.executionKind == ExecutionKind.Acp) Lucide.SquareTerminal else Lucide.ArrowLeftRight, runsOn(agent, connections, host.server), null) {
            host.onSection(agentId, SettingsSection.Harnesses)
        }
        val r = readiness(agent.readiness.state(), harness, machine, agent.name,
            (state.connection?.config?.connection as? ExecutionConnection.Acp)?.command?.firstOrNull())
        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            ReadinessNote(r, state.checking, { a -> if (a == ReadinessAction.CheckAgain) actions.onCheck() else host.onSection(agentId, SettingsSection.Harnesses) })
        }
        if (agent.executionKind == ExecutionKind.Acp) {
            Label("Working folder")
            Input(draftField(agentId, "cwd", draft.cwd) { v -> actions.onEdit { it.copy(cwd = v) } }, "/Users/you/project")
            cwdProblem(draft.cwd)?.let { Text(it, fontSize = 12.sp, color = color(destructiveColor)) }
            Text("On $machine. $harness reads and edits files here.", fontSize = 12.sp, color = color(mutedColor))
        }
        details.remoteAgentId?.let { Text("Remote Agent: $it", Modifier.padding(horizontal = 10.dp), fontSize = 13.sp) }
    }
    Group("Approvals") {
        Text("Kinuvo's Risk tolerance and Trust rules don't apply. $harness decides when to ask before acting; answer from any Device with access.",
            Modifier.padding(4.dp), fontSize = 13.sp)
    }
    Group("Managed by $harness") {
        Text("Instructions, Memory, Skills and tools are set in $harness on $machine.", Modifier.padding(4.dp), fontSize = 13.sp, color = color(mutedColor))
    }
    Text("Changes apply to ${agent.name}'s new Sessions.", fontSize = 12.sp, color = color(mutedColor))
}

@Composable
private fun ModelPage(state: InspectorState, draft: AgentDraft, actions: InspectorActions) {
    if (state.models.providers.isEmpty()) Text("Loading models…", color = color(mutedColor))
    state.models.providers.forEach { group ->
        SectionLabel(group.name)
        group.models.forEach { model ->
            val picked = group.id == draft.provider && model.id == draft.model
            ListRow(picked, {
                actions.onEdit { it.copy(provider = group.id, model = model.id, reasoning = model.reasoningFor(it.reasoning)) }
                actions.onBack()
            }) {
                Text(model.name, Modifier.weight(1f))
                if (picked) Icon(Lucide.Check, Modifier.size(14.dp))
            }
        }
        group.warning?.let { Text(it, fontSize = 12.sp, color = color(mutedColor)) }
    }
    if (state.models.model(draft.provider, draft.model) == null) SectionLabel("Current: ${draft.model}")
    ListRow(false, { actions.onLoadModels(true) }, leading = { Icon(Lucide.RefreshCw, Modifier.size(14.dp)) }) { Text("Refresh models", Modifier.weight(1f)) }
}

/** Asks what to do with unsaved edits before the inspector closes, switches Agent or the host leaves. */
@Composable
fun InspectorGuard(state: InspectorState, host: InspectorHost, actions: InspectorActions) {
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = state.guard != null) { actions.onResolve(GuardChoice.Keep) }
    AnimatedVisibility(state.guard != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(color(scrimColor)).clickable(remember { MutableInteractionSource() }, indication = null) { actions.onResolve(GuardChoice.Keep) },
            contentAlignment = Alignment.Center) {
            Panel(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 400.dp).clickable(remember { MutableInteractionSource() }, indication = null) {}.semantics { paneTitle = "Unsaved changes" },
                contentPadding = PaddingValues(18.dp)) {
                Text("Discard changes?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("Your changes have not been saved.", color = color(mutedColor), fontSize = 14.sp)
                state.errors.forEach { Spacer(Modifier.height(8.dp)); ErrorMessage(it) }
                Spacer(Modifier.height(16.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ actions.onResolve(GuardChoice.Keep) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Keep editing") }
                    Button({ actions.onResolve(GuardChoice.Discard) }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) { Text("Discard") }
                }
            }
        }
    }
}

/** A text field following the draft: edits go to [onChange], and a reset draft (discarded or saved) replaces its text. */
@Composable
internal fun draftField(agentId: String, name: String, value: String, onChange: (String) -> Unit): TextFieldState {
    val field = key(agentId, name) { rememberTextFieldState(value) }
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(field, value) { if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value) }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { if (it != latest) change(it) } }
    return field
}

@Composable
private fun Group(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color(mutedColor))
            hint?.let { Text(it, fontSize = 12.sp, color = color(mutedColor)) }
        }
        Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(start = 2.dp, top = 2.dp), fontSize = 12.sp, color = color(mutedColor))

/** A row opening a page inside the inspector. */
@Composable
private fun NavRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    ListRow(false, onClick, leading = { Icon(icon, Modifier.size(16.dp)) }) {
        Text(title, Modifier.weight(1f), singleLine = true)
        value?.let { Text(it, Modifier.widthIn(max = 200.dp), fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End) }
        Icon(Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
    }
}

private fun InspectorPage.title() = when (this) {
    InspectorPage.Agent -> "Agent settings"
    InspectorPage.Model -> "Model"
    InspectorPage.Approvals -> "Risk tolerance"
    InspectorPage.UserNotes -> "User Notes"
}
