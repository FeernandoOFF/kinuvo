package kinuvo.ui.agent

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.navigation.*
import kinuvo.ui.connection.*
import kinuvo.ui.device.*
import kinuvo.ui.session.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Clock

@Composable
fun SettingsScreen(
    app: AppViewModel,
    settings: SettingsViewModel,
    onNavigate: (Route.Settings, push: Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenSession: (Route.Chat) -> Unit,
    sidebar: Boolean = true,
) {
    val appState by app.state.collectAsState()
    val state by settings.state.collectAsState()
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(15_000)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }
    val route = state.route
    val show = { r: Route.Settings -> onNavigate(r, false) }
    SettingsContent(
        SettingsUi(state, appState.agents, now, appState.capabilities, (appState.connection as? Connection.Ready)?.server, appState.pairing, appState.pairingBase, appState.pairingError, appState.devices, (appState.connection as? Connection.Ready)?.client?.lastConnect?.management == true, appState.servers, appState.sharing),
        SettingsActions(
            onBack = onBack,
            onRenameServer = app::rename,
            onForgetServer = { app.forget(it) },
            onPickServer = settings::pickServer,
            onShare = app::share,
            onRefreshPairing = app::refreshPairing,
            onPairingBase = app::selectPairingBase,
            onRevokeDevice = app::revokeDevice,
            onRotateToken = app::rotateToken,
            onManagementPairing = app::managementPairing,
            onNavigate = onNavigate,
            onSaveAgent = settings::saveAgent,
            onRefreshModels = { settings.loadModels(true) },
            onCreateAgent = { p -> settings.createAgent(p) { id -> show(Route.Settings(id, SettingsSection.Overview)) } },
            onDeleteAgent = {
                settings.deleteAgent { appState.agents.firstOrNull { it.id != route.agentId }?.let { show(Route.Settings(it.id, SettingsSection.Overview)) } }
            },
            onEnabled = settings::setEnabled,
            onApprovals = settings::setApprovals,
            onSaveRoutine = { id, spec -> settings.saveRoutine(id, spec) { saved -> show(route.copy(item = saved, editing = false)) } },
            onRunNow = settings::runNow,
            onRemove = { id -> settings.remove(id) { show(route.copy(item = null)) } },
            onOpenSession = { id -> onOpenSession(Route.Chat(route.agentId, id)) },
            onSaveSkill = { scope, name, content -> settings.saveSkill(scope, name, content) { key -> show(route.copy(item = key, editing = false)) } },
            onDeleteSkill = { scope, name -> settings.deleteSkill(scope, name) { show(route.copy(item = null)) } },
            onSaveMcp = { server -> settings.saveMcp(server) { key -> show(route.copy(item = key, editing = false)) } },
            onRemoveMcp = { scope, name -> settings.removeMcp(scope, name) { show(route.copy(item = null)) } },
            onTestMcp = settings::testMcp,
            onSignInMcp = settings::signInMcp,
            onCompleteMcpSignIn = settings::completeMcpSignIn,
            onCancelMcpSignIn = settings::cancelMcpSignIn,
            onSignOutMcp = settings::signOutMcp,
            onLoadLists = settings::loadLists,
            onAuthorizeTasks = settings::authorizeTasks,
            onSaveTasks = settings::saveTasks,
            onSaveKnowledge = settings::saveKnowledge,
            onTestKnowledge = settings::testKnowledge,
            onTestExec = settings::testExec,
            onSaveService = settings::saveService,
            onTestService = settings::testService,
            onReloadServer = settings::reloadServer,
            onLoginProvider = settings::loginProvider,
            onLogoutProvider = settings::logoutProvider,
            onSaveProviderApiKey = settings::saveProviderApiKey,
            onLoadSecrets = settings::loadSecrets,
            onConnectSecrets = settings::connectSecrets,
            onDisconnectSecrets = settings::disconnectSecrets,
            onRefreshSecrets = settings::refreshSecrets,
            onBrowseSecrets = settings::browseSecrets,
        ),
        sidebar,
    )
}

/** What Settings shows; [now] (epoch ms) anchors relative times. */
data class SettingsUi(val state: SettingsState, val agents: List<AgentInfo> = emptyList(), val now: Long = 0, val capabilities: ServerCapabilities? = null,
    val server: Server? = null, val pairing: PairingInfo? = null, val pairingBase: String? = null,
    val pairingError: String? = null, val devices: DevicesState = DevicesState(), val management: Boolean = true,
    /** Every Server saved on this Device. */
    val servers: List<Server> = emptyList(),
    /** Whether the embedded Server is reachable from other Devices; null when it can't be. */
    val sharing: Boolean? = null,
) {
    fun available(section: SettingsSection) = capabilities.allows(section)
    val route get() = state.route
    val agent get() = agents.firstOrNull { it.id == route.agentId }
    val config get() = state.config
    val error get() = state.errors.lastOrNull()
}

class SettingsActions(
    val onRenameServer: (Server.Remote, String) -> Unit = { _, _ -> },
    val onForgetServer: (Server.Remote) -> Unit = {},
    val onPickServer: (String) -> Unit = {},
    val onShare: (Boolean) -> Unit = {},
    val onRefreshPairing: (Boolean) -> Unit = {},
    val onPairingBase: (String) -> Unit = {},
    val onRevokeDevice: (PairedDeviceInfo) -> Unit = {},
    val onRotateToken: () -> Unit = {},
    val onManagementPairing: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onNavigate: (Route.Settings, push: Boolean) -> Unit = { _, _ -> },
    val onSaveAgent: (UpdateAgentParams) -> Unit = {},
    val onRefreshModels: () -> Unit = {},
    val onCreateAgent: (CreateAgentParams) -> Unit = {},
    val onDeleteAgent: () -> Unit = {},
    val onEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onApprovals: (String, RoutineApprovals) -> Unit = { _, _ -> },
    /** Creates a Routine when the ID is null. */
    val onSaveRoutine: (String?, RoutineSpec) -> Unit = { _, _ -> },
    val onRunNow: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
    val onSaveSkill: (ConfigScope, String, String) -> Unit = { _, _, _ -> },
    val onDeleteSkill: (ConfigScope, String) -> Unit = { _, _ -> },
    val onSaveMcp: (McpServerInfo) -> Unit = {},
    val onRemoveMcp: (ConfigScope, String) -> Unit = { _, _ -> },
    val onTestMcp: (ConfigScope, String) -> Unit = { _, _ -> },
    val onSignInMcp: (ConfigScope, String) -> Unit = { _, _ -> },
    /** Finishes a sign-in with the URL the browser ended on. */
    val onCompleteMcpSignIn: (String) -> Unit = {},
    val onCancelMcpSignIn: () -> Unit = {},
    val onSignOutMcp: (ConfigScope, String) -> Unit = { _, _ -> },
    val onAuthorizeTasks: (provider: String) -> Unit = {},
    val onLoadLists: (provider: String) -> Unit = {},
    val onSaveTasks: (TasksSettings) -> Unit = {},
    val onSaveKnowledge: (KnowledgeSettings) -> Unit = {},
    val onTestKnowledge: (KnowledgeSettings) -> Unit = {},
    val onTestExec: (TestExecParams) -> Unit = {},
    val onSaveService: (SaveServiceParams) -> Unit = {},
    val onTestService: (TestServiceParams) -> Unit = {},
    val onReloadServer: () -> Unit = {},
    val onLoginProvider: (String) -> Unit = {},
    val onLogoutProvider: (String) -> Unit = {},
    val onSaveProviderApiKey: (String, String) -> Unit = { _, _ -> },
    val onLoadSecrets: () -> Unit = {},
    val onConnectSecrets: (scheme: String, token: String) -> Unit = { _, _ -> },
    val onDisconnectSecrets: (scheme: String) -> Unit = {},
    val onRefreshSecrets: () -> Unit = {},
    /** Loads the names under a path: scheme, then vault and item. */
    val onBrowseSecrets: (path: List<String>) -> Unit = {},
)

/** How a pane moves between levels: [go] opens a route at this screen width, [back] leaves the pane (null when it's beside its list). */
internal class Pane(val ui: SettingsUi, val actions: SettingsActions, val go: (Route.Settings) -> Unit, val back: (() -> Unit)?) {
    /** Leaves a form without saving. */
    val cancel: () -> Unit get() = back ?: { go(ui.route.copy(editing = false)) }
}

/**
 * List-detail settings: sections in a sidebar ≥720dp, with the open Agent's own sections under it, and a section's
 * items beside the open item ≥1080dp, replacing each other in place. Narrower, each level is its own screen, pushed
 * onto the back stack. Without the [sidebar], only the route's section shows.
 */
@Composable
fun SettingsContent(ui: SettingsUi, actions: SettingsActions, sidebar: Boolean = true) {
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val wide = sidebar && maxWidth >= 720.dp
        val split = maxWidth >= 1080.dp
        // Beside the sections, moving around replaces the screen; narrower, each level is pushed to go back from.
        val go = { route: Route.Settings -> actions.onNavigate(route, !wide) }
        val section = ui.route.section ?: SettingsSection.entries.firstOrNull { it.listed && ui.available(it) }?.takeIf { wide }
        val sections: @Composable () -> Unit = { SettingsSections(ui, section, go, actions.onBack) }
        val content: @Composable (SettingsSection) -> Unit = { SectionPane(it, ui, split, wide, go, actions) }
        if (wide) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(260.dp).fillMaxHeight().background(color(sidebarColor))) { sections() }
            VerticalSeparator()
            Box(Modifier.weight(1f)) { if (section != null) content(section) }
        } else if (section == null) sections()
        else content(section)
    }
}

@Composable
private fun SettingsSections(ui: SettingsUi, selected: SettingsSection?, go: (Route.Settings) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(trailing = false) {
            HeaderButton(Lucide.ArrowLeft, "Back ${shortcut("[")}", onBack)
            Text("Settings", Modifier.padding(start = 4.dp), fontWeight = FontWeight.Normal)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            if (ui.agents.isNotEmpty()) SectionLabel("Agents")
            ui.agents.forEach { agent ->
                val open = ui.route.agentId == agent.id && (selected == SettingsSection.Overview || selected?.agent == true)
                ListRow(
                    selected = open && selected == SettingsSection.Overview,
                    onClick = { go(Route.Settings(agent.id, SettingsSection.Overview)) },
                    leading = { AgentAvatar(agent, 20.dp) },
                ) {
                    Text(agent.name, Modifier.weight(1f), singleLine = true, overflow = TextOverflow.Ellipsis)
                }
                // The open Agent's own sections, nested under it.
                if (open) SettingsSection.entries.filter { it.agent }.forEach { section ->
                    ListRow(
                        selected = section == selected,
                        onClick = { go(Route.Settings(agent.id, section)) },
                        modifier = Modifier.padding(start = 20.dp),
                        enabled = ui.available(section),
                        leading = { Icon(section.icon(), Modifier.size(16.dp)) },
                    ) {
                        Text(section.title, Modifier.weight(1f), singleLine = true)
                        section.count(ui)?.let { Text("$it", fontSize = 12.sp, color = color(mutedColor)) }
                    }
                }
            }
            SectionLabel("Device", Modifier.padding(top = if (ui.agents.isNotEmpty()) 12.dp else 0.dp))
            SettingsSection.entries.filter { it.listed }.forEach { section ->
                if (section == SettingsSection.entries.first { it.server }) SectionLabel("Server", Modifier.padding(top = 12.dp))
                ListRow(
                    selected = section == selected,
                    onClick = { go(ui.route.copy(section = section, item = null, editing = false)) },
                    enabled = ui.available(section),
                    leading = { Icon(section.icon(), Modifier.size(16.dp)) },
                ) {
                    Text(section.title, Modifier.weight(1f), singleLine = true)
                    section.count(ui)?.let { Text("$it", fontSize = 12.sp, color = color(mutedColor)) }
                }
            }
        }
    }
}

private fun SettingsSection.icon(): ImageVector = when (this) {
    SettingsSection.Connection -> Lucide.Server
    SettingsSection.Overview -> Lucide.Bot
    SettingsSection.Agent -> Lucide.Sparkles
    SettingsSection.Workspace -> Lucide.FolderOpen
    SettingsSection.Tools -> Lucide.Wrench
    SettingsSection.Routines -> Lucide.AlarmClock
    SettingsSection.Skills -> Lucide.BookOpen
    SettingsSection.Mcp -> Lucide.Plug
    SettingsSection.Tasks -> Lucide.SquareKanban
    SettingsSection.Knowledge -> Lucide.Library
    SettingsSection.Providers -> Lucide.Sparkles
    SettingsSection.Secrets -> Lucide.KeyRound
    SettingsSection.Search -> Lucide.Search
    SettingsSection.Notifications -> Lucide.Bell
    SettingsSection.DeviceTools -> Lucide.Plug
}

private fun SettingsSection.count(ui: SettingsUi): Int? = when (this) {
    SettingsSection.Overview, SettingsSection.Agent, SettingsSection.Workspace, SettingsSection.Tools, SettingsSection.DeviceTools -> null
    SettingsSection.Routines -> ui.state.routines?.count { it.enabled }
    SettingsSection.Skills -> ui.config.skills?.count { it.active }
    SettingsSection.Mcp -> ui.config.mcp?.count { it.active }
    SettingsSection.Connection, SettingsSection.Providers, SettingsSection.Tasks, SettingsSection.Knowledge, SettingsSection.Secrets, SettingsSection.Search, SettingsSection.Notifications -> null
}

@Composable
private fun SectionPane(
    section: SettingsSection, ui: SettingsUi, split: Boolean, wide: Boolean, go: (Route.Settings) -> Unit, actions: SettingsActions,
    /** Leaves the section's first level: the way out of the screen, or null beside the sections. */
    rootBack: (() -> Unit)? = actions.onBack.takeIf { !wide },
) {
    val route = ui.route
    if (section == SettingsSection.DeviceTools) return DeviceToolsSettingsPane(Pane(ui, actions, go, rootBack))
    if (section == SettingsSection.Overview) return AgentContent(ui, { actions.onNavigate(it, true) }, rootBack)
    if (ui.capabilities != null && !ui.available(section)) return DetailPane(rootBack, section.title) { Text(UNAVAILABLE, color = color(mutedColor)) }
    if (section == SettingsSection.Agent || section == SettingsSection.Workspace || section == SettingsSection.Tools) {
        val pane = Pane(ui, actions, go, rootBack)
        return if (route.editing && section == SettingsSection.Agent) NewAgentForm(pane) else AgentPane(pane, section)
    }
    if (section.server) {
        val pane = Pane(ui, actions, go, rootBack)
        return when (section) {
            SettingsSection.Connection -> ConnectionSettingsPane(pane)
            SettingsSection.Providers -> ProvidersSettingsPane(pane)
            SettingsSection.Tasks -> TasksSettingsPane(pane)
            SettingsSection.Knowledge -> KnowledgeSettingsPane(pane)
            SettingsSection.Search -> ServiceSettingsPane(pane, ServiceKind.Search)
            SettingsSection.Notifications -> ServiceSettingsPane(pane, ServiceKind.Notifications)
            else -> SecretsSettingsPane(pane)
        }
    }
    val open = route.item != null || route.editing
    // From a form back to its item, from an item back to the list.
    val up = if (wide) ({ go(route.copy(item = route.item.takeIf { route.editing }, editing = false)) }) else actions.onBack
    val list: @Composable (Pane) -> Unit = { p ->
        when (section) {
            SettingsSection.Routines -> RoutineList(p)
            SettingsSection.Skills -> SkillList(p)
            else -> McpList(p)
        }
    }
    val detail: @Composable (Pane) -> Unit = { p ->
        when (section) {
            SettingsSection.Routines -> RoutinePane(p)
            SettingsSection.Skills -> SkillPane(p)
            else -> McpPane(p)
        }
    }
    if (split) Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(360.dp).fillMaxHeight()) { list(Pane(ui, actions, go, rootBack)) }
        VerticalSeparator()
        Box(Modifier.weight(1f)) {
            // Beside the list, only editing an existing item has somewhere to go back to.
            if (open) detail(Pane(ui, actions, go, if (route.editing && route.item != null && presetOf(route.item) == null) up else null))
            else NothingSelected("Select ${section.noun()}")
        }
    } else if (!open) list(Pane(ui, actions, go, rootBack))
    else detail(Pane(ui, actions, go, up))
}

private fun SettingsSection.noun() = when (this) {
    SettingsSection.Overview, SettingsSection.Agent, SettingsSection.Workspace, SettingsSection.Tools -> "an agent"
    SettingsSection.Routines -> "a routine"
    SettingsSection.Skills -> "a skill"
    SettingsSection.Mcp -> "an MCP server"
    SettingsSection.Connection, SettingsSection.Providers, SettingsSection.Tasks, SettingsSection.Knowledge, SettingsSection.Secrets, SettingsSection.Search, SettingsSection.Notifications, SettingsSection.DeviceTools -> "a setting"
}

/** A section's list: title with an add button, an intro, then its rows or a spinner while [loading]. */
@Composable
internal fun ItemList(p: Pane, title: String, intro: String, addLabel: String, loading: Boolean, rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(leading = p.back != null) {
            p.back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Text(title, Modifier.weight(1f).padding(start = 4.dp), fontWeight = FontWeight.Normal)
            HeaderButton(Lucide.Plus, addLabel, { p.go(p.ui.route.copy(item = null, editing = true)) })
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            item { Text(intro, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), color = color(mutedColor), fontSize = 13.sp) }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() } }
            else rows()
        }
    }
}

/** A list row with a title, a muted [subtitle] and trailing pills. */
@Composable
internal fun ItemRow(selected: Boolean, icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    ListRow(selected = selected, onClick = onClick, leading = { Icon(icon, Modifier.size(16.dp)) }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/**
 * A detail or form screen: a title bar with [trailing] controls over a centered, scrolling column, led by any [error].
 * [info] describes the screen behind a (?) by the title.
 */
@Composable
internal fun DetailPane(back: (() -> Unit)?, title: String, error: String? = null, info: String? = null, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TitleBarRow(leading = back != null) {
            back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Row(Modifier.weight(1f).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, Modifier.weight(1f, fill = false), fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                info?.let { InfoButton(it) }
            }
            trailing()
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                error?.let { ErrorMessage(it) }
                content()
            }
        }
    }
}

@Composable
internal fun DetailGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title, Modifier.padding(start = 0.dp))
        Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(120.dp), color = color(mutedColor), fontSize = 13.sp)
        Box(Modifier.weight(1f)) { value() }
    }
}

/** A labelled form control; its [hint] opens from a (?) by the label. */
@Composable
internal fun FormField(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Normal)
            hint?.let { InfoButton(it) }
        }
        content()
    }
}

/** A (?) showing [text] when tapped: over the app on touch screens, by the button otherwise. */
@Composable
internal fun InfoButton(text: String) {
    var open by remember { mutableStateOf(false) }
    val trigger: @Composable () -> Unit = {
        IconButton(onClick = { open = !open }, style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(mutedColor), modifier = Modifier.size(24.dp)) {
            Icon(Lucide.CircleQuestionMark, Modifier.size(14.dp), contentDescription = "About this")
        }
    }
    if (LocalInteractionMode.current == InteractionMode.Touch && LocalPopovers.current != null) {
        trigger()
        Popover(open, { open = false }) { Text(text, fontSize = 14.sp) }
    } else DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = { DropdownMenuPanel { Text(text, Modifier.widthIn(max = 320.dp).padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp) } },
    ) { trigger() }
}

/** Multi-line bordered input; [mono] for code-like text such as SKILL.md, which isn't capitalized. */
@Composable
internal fun TextArea(state: TextFieldState, placeholder: String = "", lines: Int = 5, mono: Boolean = false, capitalize: Boolean = !mono, trailing: (@Composable () -> Unit)? = null) {
    val style = if (mono) MonoStyle else inputTextStyle()
    TextField(
        state,
        Modifier.fillMaxWidth(),
        trailing = trailing,
        backgroundColor = color(backgroundColor),
        borderColor = color(borderColor),
        placeholder = { Text(placeholder, style = style) },
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = lines, maxHeightInLines = 30),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        textStyle = style,
        keyboardOptions = inputKeyboard(capitalize),
    )
}

/** A dropdown of [options]; [hint] adds a muted second line to each. */
@Composable
internal fun <T> Picker(options: List<T>, current: T, label: (T) -> String, onSelect: (T) -> Unit, hint: ((T) -> String)? = null) {
    var open by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            DropdownMenuPanel {
                options.forEach { option ->
                    DropdownMenuItem(
                        onClick = { open = false; onSelect(option) },
                        trailing = if (option == current) ({ Icon(Lucide.Check, Modifier.size(14.dp)) }) else null,
                    ) {
                        Column {
                            Text(label(option))
                            hint?.invoke(option)?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, color = color(mutedColor)) }
                        }
                    }
                }
            }
        },
    ) {
        Button(onClick = { open = true }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(label(current))
            Spacer(Modifier.width(4.dp))
            Icon(Lucide.ChevronDown, Modifier.size(14.dp), tint = color(mutedColor))
        }
    }
}

/** Save and Cancel for a form; [error] is the last failure. */
@Composable
internal fun FormButtons(saveLabel: String, enabled: Boolean, error: String?, onSave: () -> Unit, onCancel: (() -> Unit)?) {
    error?.let { ErrorMessage(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = enabled, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text(saveLabel) }
        onCancel?.let { Button(onClick = it, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Cancel") } }
    }
}

/** A form's title-bar actions: "Saved" after a save, Cancel when [onCancel] is set, and the primary button. */
@Composable
internal fun FormActions(saveLabel: String, enabled: Boolean, saved: Boolean, onSave: () -> Unit, onCancel: (() -> Unit)? = null) {
    Row(Modifier.titleBarControl(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (saved) Pill("Saved", tone = Tone.Success, icon = Lucide.CircleCheck)
        onCancel?.let { Button(onClick = it, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Cancel") } }
        Button(onClick = onSave, enabled = enabled, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text(saveLabel) }
    }
}

/** A small button with an icon. */
@Composable
internal fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, singleLine = true)
    }
    if (destructive) Button(onClick = onClick, enabled = enabled, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(destructiveColor), content = content)
    else Button(onClick = onClick, enabled = enabled, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small, content = content)
}

/** Delete that asks once more before calling [onDelete]. */
@Composable
internal fun DeleteButton(what: String, key: Any, onDelete: () -> Unit) {
    var confirm by remember(key) { mutableStateOf(false) }
    ActionButton(Lucide.Trash2, if (confirm) "Delete this $what?" else "Delete", { if (confirm) onDelete() else confirm = true }, destructive = true)
}

@Composable
internal fun PaneLoading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }

@Composable
private fun NothingSelected(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color(mutedColor))
    }
}

internal fun ConfigScope.label() = when (this) {
    ConfigScope.Agent -> "This agent"
    ConfigScope.Shared -> "Shared"
    ConfigScope.External -> "External"
}

internal fun TextFieldState.value() = text.toString().trim()

/** Non-blank lines, e.g. one argument or `KEY=value` per line. */
internal fun TextFieldState.lines() = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

internal fun TextFieldState.commaList() = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** "in 2h 5m", "3d ago", "now". */
fun relative(at: Long, now: Long): String {
    val minutes = abs(at - now) / 60_000
    val text = when {
        minutes < 1 -> return "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h" + (minutes % 60).let { if (it > 0) " ${it}m" else "" }
        else -> "${minutes / 1440}d" + (minutes % 1440 / 60).let { if (it > 0) " ${it}h" else "" }
    }
    return if (at < now) "$text ago" else "in $text"
}
