package kinuvo.ui.agent

import kinuvo.app.agent.*
import kinuvo.ui.settings.*
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
import androidx.compose.ui.graphics.Color
import com.composeunstyled.theme.Theme
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
        subtitle = if (state.pages.isEmpty()) "Agent settings for every Session" else "${agent?.name ?: "Agent"} · every Session",
    ) {
        if (state.dirty) SaveButton(actions.onSave, enabled = !state.saving && state.draft?.problems(state.agentId.orEmpty(), host.agents)?.isEmpty() == true)
        else if (state.pages.isEmpty()) HeaderButton(Lucide.X, "Close", actions.onClose)
    }
}

@Composable
private fun InspectorBody(state: InspectorState, host: InspectorHost, actions: InspectorActions) {
    val draft = state.draft
    val details = state.details
    if (draft == null || details == null) return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.space16, vertical = Spacing.space14), verticalArrangement = Arrangement.spacedBy(Spacing.space16)) {
        state.errors.forEach { ErrorMessage(it) }
        when (state.page) {
            InspectorPage.Agent -> if (details.info.external) ExternalAgentPage(state, draft, details, host, actions) else AgentPage(state, draft, details, host, actions)
            InspectorPage.Instructions -> {
                val agentId = details.info.id
                RowGroup("Personality") {
                    FieldRow("Who ${draft.name.ifBlank { "the Agent" }} is") { TextArea(draftField(agentId, "identity", draft.identity) { v -> actions.onEdit { it.copy(identity = v) } }, lines = 6) }
                }
                RowGroup("Soul") {
                    FieldRow("Principles, tone and rules") { TextArea(draftField(agentId, "soul", draft.soul) { v -> actions.onEdit { it.copy(soul = v) } }, lines = 12) }
                }
            }
            InspectorPage.Model -> ModelPage(state, draft, actions)
            InspectorPage.Approvals -> RowGroup("Ask before") {
                APPROVALS.forEach { (key, hint) ->
                    GroupRow(key.replaceFirstChar { it.uppercase() }, { actions.onEdit { it.copy(approvals = key) }; actions.onBack() }, hint,
                        selected = draft.approvals == key, chevron = false, titleLines = 1) {
                        if (draft.approvals == key) Icon(Lucide.Check, Modifier.size(16.dp), tint = color(accentColor))
                    }
                }
            }
            InspectorPage.UserNotes -> Text(details.userNotes.ifBlank { "${details.info.name} hasn't written any User Notes yet." }, style = TextStyles.label,
                color = color(if (details.userNotes.isBlank()) mutedColor else onBackgroundColor))
        }
    }
}

@Composable
private fun AgentPage(state: InspectorState, draft: AgentDraft, details: AgentDetails, host: InspectorHost, actions: InspectorActions) {
    val agent = host.agents.firstOrNull { it.id == details.info.id } ?: details.info
    val agentId = details.info.id
    AvatarPicker(agent, draft, actions)
    // Labelled fields in grouped rows, like every other Settings form.
    RowGroup("Identity") {
        FieldRow("Name") {
            Input(draftField(agentId, "name", draft.name) { v -> actions.onEdit { it.copy(name = v) } }, "Name", capitalize = true)
            draft.problems(agentId, host.agents).filter { it.contains("Name", true) || it.contains("called") }.forEach {
                Text(it, style = TextStyles.caption, color = color(destructiveColor))
            }
        }
    }
    Group("Instructions") {
        NavRow(Lucide.ScrollText, "Personality and Soul", draft.identity.lineSequence().map { it.trim().trimStart('#', ' ') }.firstOrNull { it.isNotEmpty() }) {
            actions.onPush(InspectorPage.Instructions)
        }
    }
    Group("Model") {
        val selected = state.models.model(draft.provider, draft.model)
        val provider = state.models.providers.firstOrNull { it.id == draft.provider }?.name ?: draft.provider
        NavRow(Lucide.Cpu, "Model", "${selected?.name ?: draft.model} · $provider") { actions.onPush(InspectorPage.Model) }
        val levels = selected?.reasoning ?: (REASONING + draft.reasoning).distinct()
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.space10, vertical = Spacing.space4), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Brain, Modifier.size(16.dp), tint = color(mutedColor))
            Text("Reasoning", Modifier.weight(1f).padding(start = Spacing.space10), style = TextStyles.callout)
            if (levels.isEmpty()) Text("Not supported", color = color(mutedColor), style = TextStyles.secondary)
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
        style = TextStyles.caption, color = color(mutedColor))
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
    AvatarPicker(agent, draft, actions)
    Group("Identity", card = false) {
        Label("Name")
        Input(draftField(agentId, "name", draft.name) { v -> actions.onEdit { it.copy(name = v) } }, "Name", capitalize = true)
        problems.filter { it.contains("Name", true) || it.contains("called") }.forEach { Text(it, style = TextStyles.caption, color = color(destructiveColor)) }
    }
    Group("Runs on") {
        NavRow(if (agent.executionKind == ExecutionKind.Acp) Lucide.SquareTerminal else Lucide.ArrowLeftRight, runsOn(agent, connections, host.server), null) {
            host.onSection(agentId, SettingsSection.Harnesses)
        }
        val r = readiness(agent.readiness.state(), harness, machine, agent.name,
            (state.connection?.config?.connection as? ExecutionConnection.Acp)?.command?.firstOrNull())
        Box(Modifier.padding(horizontal = Spacing.space10, vertical = Spacing.space4)) {
            ReadinessNote(r, state.checking, { a -> if (a == ReadinessAction.CheckAgain) actions.onCheck() else host.onSection(agentId, SettingsSection.Harnesses) })
        }
        if (agent.executionKind == ExecutionKind.Acp) {
            Label("Working folder")
            Input(draftField(agentId, "cwd", draft.cwd) { v -> actions.onEdit { it.copy(cwd = v) } }, "/Users/you/project")
            cwdProblem(draft.cwd)?.let { Text(it, style = TextStyles.caption, color = color(destructiveColor)) }
            Text("On $machine. $harness reads and edits files here.", style = TextStyles.caption, color = color(mutedColor))
        }
        details.remoteAgentId?.let { Text("Remote Agent: $it", Modifier.padding(horizontal = Spacing.space10), style = TextStyles.secondary) }
    }
    Group("Approvals") {
        Text("Kinuvo's Risk tolerance and Trust rules don't apply. $harness decides when to ask before acting; answer from any Device with access.",
            Modifier.padding(Spacing.space4), style = TextStyles.secondary)
    }
    Group("Managed by $harness") {
        Text("Instructions, Memory, Skills and tools are set in $harness on $machine.", Modifier.padding(Spacing.space4), style = TextStyles.secondary, color = color(mutedColor))
    }
    Text("Changes apply to ${agent.name}'s new Sessions.", style = TextStyles.caption, color = color(mutedColor))
}

/** The Agent's face, with the others a tap away on either side. */
@Composable
private fun AvatarPicker(agent: AgentInfo, draft: AgentDraft, actions: InspectorActions) {
    val current = AvatarArtboards.indexOf(draft.avatar).coerceAtLeast(0)
    val step = { delta: Int -> actions.onEdit { it.copy(avatar = AvatarArtboards[(current + delta + AvatarArtboards.size) % AvatarArtboards.size]) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.space16, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        HeaderButton(Lucide.ChevronLeft, "Previous avatar", { step(-1) })
        AgentAvatar(agent.copy(avatar = draft.avatar.ifEmpty { agent.avatar }), 64.dp)
        HeaderButton(Lucide.ChevronRight, "Next avatar", { step(1) })
    }
}

@Composable
private fun ModelPage(state: InspectorState, draft: AgentDraft, actions: InspectorActions) {
    if (state.models.providers.isEmpty()) Text("Loading models…", color = color(mutedColor))
    state.models.providers.forEach { group ->
        SectionLabel(group.name)
        group.chatModels.forEach { model ->
            val picked = group.id == draft.provider && model.id == draft.model
            ListRow(picked, {
                actions.onEdit { it.copy(provider = group.id, model = model.id, reasoning = model.reasoningFor(it.reasoning)) }
                actions.onBack()
            }) {
                Column(Modifier.weight(1f)) {
                    Text(model.name)
                    model.state.reason?.let { Text(it, fontSize = 12.sp, color = color(mutedColor)) }
                }
                if (picked) Icon(Lucide.Check, Modifier.size(14.dp))
            }
        }
        group.warning?.let { Text(it, style = TextStyles.caption, color = color(mutedColor)) }
    }
    if (state.models.model(draft.provider, draft.model) == null) SectionLabel("Current: ${draft.model}")
    ListRow(false, { actions.onLoadModels(true) }, leading = { Icon(Lucide.RefreshCw, Modifier.size(14.dp)) }) { Text("Refresh models", Modifier.weight(1f)) }
}

/** Asks what to do with unsaved edits before the inspector closes, switches Agent or the host leaves. */
@Composable
fun InspectorGuard(state: InspectorState, host: InspectorHost, actions: InspectorActions) {
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = state.guard != null) { actions.onResolve(GuardChoice.Keep) }
    AnimatedVisibility(state.guard != null, enter = fadeIn(), exit = fadeOut()) {
        DiscardDialog({ actions.onResolve(GuardChoice.Keep) }, { actions.onResolve(GuardChoice.Discard) }, errors = state.errors)
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
private fun Group(title: String, hint: String? = null, card: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        // Labelled and filled like a RowGroup; fields go without the card, as their own borders already contain them.
        Row(Modifier.padding(start = Spacing.space6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            SectionLabel(title)
            hint?.let { Text(it, style = TextStyles.caption, color = color(mutedColor)) }
        }
        if (!card) Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6), content = content)
        else Panel(Modifier.fillMaxWidth(), borderColor = Color.Transparent, shape = Theme[shapes][largeShape], contentPadding = PaddingValues(horizontal = Spacing.space8, vertical = Spacing.space8)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6), content = content)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(start = Spacing.space12, top = Spacing.space2), style = TextStyles.caption, color = color(mutedColor))

/** A row opening a page inside the inspector. */
@Composable
private fun NavRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    ListRow(false, onClick, leading = { Icon(icon, Modifier.size(16.dp)) }) {
        Text(title, style = TextStyles.callout, singleLine = true)
        Box(Modifier.weight(1f).padding(start = Spacing.space12), contentAlignment = Alignment.CenterEnd) {
            value?.let { Text(it, style = TextStyles.caption, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End) }
        }
        Icon(Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
    }
}

private fun InspectorPage.title() = when (this) {
    InspectorPage.Agent -> "Agent settings"
    InspectorPage.Instructions -> "Instructions"
    InspectorPage.Model -> "Model"
    InspectorPage.Approvals -> "Risk tolerance"
    InspectorPage.UserNotes -> "User Notes"
}
