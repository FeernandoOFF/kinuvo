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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
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
    /** Opens one of the Agent's Settings sections, after any unsaved edits are settled. */
    val onSection: (agentId: String, section: SettingsSection) -> Unit = { _, _ -> },
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
)

private fun AgentInspectorViewModel.actions(host: InspectorHost) = InspectorActions(
    onBack = { back() }, onClose = { close() }, onEdit = ::edit, onSave = { save(host.agents) },
    onPush = ::push, onLoadModels = { loadModels(it) }, onResolve = { resolve(it, host.agents) },
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
    val panelSemantics = if (state.guard != null) Modifier.clearAndSetSemantics {} else Modifier
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = state.open && state.guard == null, onBackCompleted = actions.onBack)
    if (sheet) BottomSheet(
        state.open, onDismissRequest = actions.onClose,
        header = { InspectorHeader(shown, host, actions) },
        footer = { InspectorFooter(shown, host, actions) },
        modifier = panelSemantics,
    ) { InspectorBody(shown, host, actions) }
    else if (state.open) Column(modifier.then(panelSemantics).width(InspectorWidth).fillMaxHeight().background(color(sidebarColor)).semantics { paneTitle = "Agent settings" }) {
        InspectorHeader(shown, host, actions)
        Box(Modifier.weight(1f)) { InspectorBody(shown, host, actions) }
        InspectorFooter(shown, host, actions)
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
    TitleBarRow(leading = state.pages.isNotEmpty(), trailing = true) {
        if (state.pages.isNotEmpty()) HeaderButton(Lucide.ArrowLeft, "Back", { actions.onBack() })
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(if (state.pages.isEmpty()) agent?.name ?: "…" else state.page.title(), fontWeight = FontWeight.Bold, singleLine = true, overflow = TextOverflow.Ellipsis)
            Text(if (state.pages.isEmpty()) "Agent settings · every Session" else "${agent?.name ?: "Agent"} · every Session", fontSize = 12.sp, color = color(mutedColor), singleLine = true)
        }
        HeaderButton(Lucide.X, "Close Agent settings", { actions.onClose() })
    }
    HorizontalSeparator()
}

@Composable
private fun InspectorFooter(state: InspectorState, host: InspectorHost, actions: InspectorActions) {
    val problems = state.draft?.problems(state.agentId.orEmpty(), host.agents).orEmpty()
    HorizontalSeparator()
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val status = when {
            state.saving -> "Saving…"
            state.dirty -> "Unsaved: " + state.changes!!.labels().joinToString()
            state.saved -> "Saved"
            else -> "No unsaved changes"
        }
        Text(status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp, color = color(if (state.dirty) warningColor else mutedColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.dirty) {
            Button({ actions.onEdit { state.details?.let(AgentDraft::of) ?: it } }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, enabled = !state.saving) { Text("Discard") }
            Button({ actions.onSave() }, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small, enabled = !state.saving && problems.isEmpty()) { Text("Save") }
        } else Button({ actions.onClose() }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) { Text("Close") }
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
            InspectorPage.Agent -> AgentPage(state, draft, details, host, actions)
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
        NavRow(Lucide.Wrench, "Tools and Trust rules", null, external = true) { host.onSection(agentId, SettingsSection.Tools) }
    }
    Group("Knows") {
        NavRow(Lucide.NotebookPen, "User Notes", if (details.userNotes.isBlank()) "None yet" else "${details.userNotes.lines().count { it.isNotBlank() }} lines") { actions.onPush(InspectorPage.UserNotes) }
        if (host.capabilities.allows(SettingsSection.Skills)) NavRow(Lucide.BookOpen, "Skills", null, external = true) { host.onSection(agentId, SettingsSection.Skills) }
    }
    Group("Does") {
        if (host.capabilities.allows(SettingsSection.Routines)) NavRow(Lucide.AlarmClock, "Routines", null, external = true) { host.onSection(agentId, SettingsSection.Routines) }
        if (host.capabilities.allows(SettingsSection.Workspace)) NavRow(Lucide.FolderOpen, "Workspace", null, external = true) { host.onSection(agentId, SettingsSection.Workspace) }
        if (host.capabilities.allows(SettingsSection.Mcp)) NavRow(Lucide.Plug, "MCP servers", null, external = true) { host.onSection(agentId, SettingsSection.Mcp) }
    }
    Text("These are ${agent.name}'s defaults for every Session. A Session's own model, reasoning and Risk tolerance are set from its composer.",
        fontSize = 12.sp, color = color(mutedColor))
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
    val agent = host.agents.firstOrNull { it.id == state.agentId }?.name ?: "this Agent"
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = state.guard != null) { actions.onResolve(GuardChoice.Keep) }
    AnimatedVisibility(state.guard != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(color(scrimColor)).clickable(remember { MutableInteractionSource() }, indication = null) { actions.onResolve(GuardChoice.Keep) },
            contentAlignment = Alignment.Center) {
            Panel(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 400.dp).clickable(remember { MutableInteractionSource() }, indication = null) {}.semantics { paneTitle = "Unsaved changes" },
                contentPadding = PaddingValues(18.dp)) {
                Text("Unsaved changes to $agent", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("Save ${state.changes?.labels()?.joinToString()?.lowercase() ?: "them"} as $agent's defaults before leaving?", color = color(mutedColor), fontSize = 14.sp)
                state.errors.forEach { Spacer(Modifier.height(8.dp)); ErrorMessage(it) }
                Spacer(Modifier.height(16.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ actions.onResolve(GuardChoice.Keep) }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Keep editing") }
                    Button({ actions.onResolve(GuardChoice.Discard) }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) { Text("Discard") }
                    Button({ actions.onResolve(GuardChoice.Save) }, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small, enabled = !state.saving && state.draft?.problems(state.agentId.orEmpty(), host.agents)?.isEmpty() == true) { Text("Save") }
                }
            }
        }
    }
}

/** A text field following the draft: edits go to [onChange], and a reset draft (discarded or saved) replaces its text. */
@Composable
private fun draftField(agentId: String, name: String, value: String, onChange: (String) -> Unit): TextFieldState {
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

/** A row opening a nested page, or with [external] the Agent's full Settings. */
@Composable
private fun NavRow(icon: ImageVector, title: String, value: String?, external: Boolean = false, onClick: () -> Unit) {
    ListRow(false, onClick, leading = { Icon(icon, Modifier.size(16.dp)) }) {
        Text(title, Modifier.weight(1f), singleLine = true)
        value?.let { Text(it, Modifier.weight(1f, fill = false), fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End) }
        Icon(if (external) Lucide.ArrowUpRight else Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
    }
}

private fun InspectorPage.title() = when (this) {
    InspectorPage.Agent -> "Agent settings"
    InspectorPage.Model -> "Model"
    InspectorPage.Approvals -> "Risk tolerance"
    InspectorPage.UserNotes -> "User Notes"
}

/** The names of the fields [this] changes, for the footer and the guard. */
private fun UpdateAgentParams.labels() = listOfNotNull(
    "Name".takeIf { name != null }, "Personality".takeIf { identity != null }, "Soul".takeIf { soul != null },
    "Model".takeIf { model != null || provider != null }, "Reasoning".takeIf { reasoning != null }, "Risk tolerance".takeIf { approvals != null },
)
