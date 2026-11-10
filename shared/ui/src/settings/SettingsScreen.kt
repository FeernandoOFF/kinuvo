package kinuvo.ui.settings

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.settings.*
import kinuvo.ui.agent.*
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kinuvo.ui.voice.ServerVoiceSettings
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
    val edits = LocalSettingsEdits.current
    val show = { r: Route.Settings -> edits?.saved(); onNavigate(r, false) }
    SettingsContent(
        SettingsUi(state, appState.agents, now, appState.capabilities, (appState.connection as? Connection.Ready)?.server, appState.pairing, appState.pairingBase, appState.pairingError, appState.devices, (appState.connection as? Connection.Ready)?.client?.lastConnect?.management == true, appState.servers, appState.sharing,
            (appState.connection as? Connection.Ready)?.let { serverLabel(it) } ?: "this Server"),
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
            onClearCache = { app.clearCache() },
            onResetDevice = { app.resetDevice() },
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
            onReloadRoutines = settings::reloadRoutines,
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
            onSaveServerRules = settings::saveServerRules,
            onMcpAccess = settings::setMcpAccess,
            onToolAccess = settings::setToolAccess,
            onSaveService = settings::saveService,
            onTestService = settings::testService,
            onReloadServer = settings::reloadServer,
            onLoginProvider = settings::loginProvider,
            onLogoutProvider = settings::logoutProvider,
            onSaveImageModel = settings::saveImageModel,
            onSaveProviderApiKey = settings::saveProviderApiKey,
            onDownloadModel = settings::downloadModel,
            onCancelModelDownload = settings::cancelModelDownload,
            onRemoveModel = settings::removeModel,
            onLoadSecrets = settings::loadSecrets,
            onConnectSecrets = settings::connectSecrets,
            onDisconnectSecrets = settings::disconnectSecrets,
            onRefreshSecrets = settings::refreshSecrets,
            onBrowseSecrets = settings::browseSecrets,
            onEditAcp = settings::editAcp,
            onCheckAcp = { settings.checkAcp() },
            onSaveAcp = { settings.saveAcp { id -> show(route.copy(item = id, editing = false)) } },
            onCheckHarness = { settings.checkHarness(it) },
            onRemoveHarness = { id -> settings.removeHarness(id) { show(route.copy(item = null, editing = false)) } },
            onEditLink = settings::editLink,
            onLink = { settings.link { id -> show(route.copy(item = id, editing = false)) } },
            onEditShare = settings::editShare,
            onShareAgents = { settings.share() },
            onRevokeShare = { settings.revokeShare(it) },
            onLoadGrants = { settings.loadGrants() },
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
    /** The selected Server's machine, as labels name it: its name, or "this device" when embedded here. */
    val serverName: String = "this Server",
) {
    fun available(section: SettingsSection) = capabilities.allows(section) && (section != SettingsSection.Harnesses || state.harnesses.catalog != null)
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
    val onClearCache: () -> Unit = {},
    val onResetDevice: () -> Unit = {},
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
    val onReloadRoutines: () -> Unit = {},
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
    val onLoadLists: (TaskProviderRef) -> Unit = {},
    val onSaveTasks: (SaveTasksParams) -> Unit = {},
    val onSaveKnowledge: (KnowledgeSettings) -> Unit = {},
    val onTestKnowledge: (KnowledgeSettings) -> Unit = {},
    val onTestExec: (TestExecParams) -> Unit = {},
    val onSaveServerRules: (TrustRules) -> Unit = {},
    val onMcpAccess: (server: String, agentId: String, on: Boolean, everyAgent: List<String>) -> Unit = { _, _, _, _ -> },
    val onToolAccess: (agentId: String, tool: String, on: Boolean) -> Unit = { _, _, _ -> },
    val onSaveService: (SaveServiceParams) -> Unit = {},
    val onTestService: (TestServiceParams) -> Unit = {},
    val onReloadServer: () -> Unit = {},
    val onLoginProvider: (String) -> Unit = {},
    val onLogoutProvider: (String) -> Unit = {},
    val onSaveImageModel: (ProviderModelRef?) -> Unit = {},
    val onSaveProviderApiKey: (String, String) -> Unit = { _, _ -> },
    val onDownloadModel: (String, String) -> Unit = { _, _ -> },
    val onCancelModelDownload: (String) -> Unit = {},
    val onRemoveModel: (String, String) -> Unit = { _, _ -> },
    val onLoadSecrets: () -> Unit = {},
    val onConnectSecrets: (scheme: String, token: String) -> Unit = { _, _ -> },
    val onDisconnectSecrets: (scheme: String) -> Unit = {},
    val onRefreshSecrets: () -> Unit = {},
    /** Loads the names under a path: scheme, then vault and item. */
    val onBrowseSecrets: (path: List<String>) -> Unit = {},
    val onEditAcp: ((AcpForm) -> AcpForm) -> Unit = {},
    val onCheckAcp: () -> Unit = {},
    val onSaveAcp: () -> Unit = {},
    val onCheckHarness: (String) -> Unit = {},
    val onRemoveHarness: (String) -> Unit = {},
    val onEditLink: ((LinkForm) -> LinkForm) -> Unit = {},
    val onLink: () -> Unit = {},
    val onEditShare: ((ShareForm) -> ShareForm) -> Unit = {},
    val onShareAgents: () -> Unit = {},
    val onRevokeShare: (String) -> Unit = {},
    val onLoadGrants: () -> Unit = {},
)

/** How a pane moves between levels: [go] opens a route at this screen width, [back] leaves the pane (null when it's beside its list). */
internal class Pane(val ui: SettingsUi, val actions: SettingsActions, val go: (Route.Settings) -> Unit, val back: (() -> Unit)?) {
    /** Leaves a form without saving. */
    val cancel: () -> Unit get() = back ?: { go(ui.route.copy(editing = false)) }
}

/**
 * List-detail settings: sections in a sidebar ≥720dp and a section's items beside the open item ≥1080dp, replacing
 * each other in place. Narrower, each level is its own screen, pushed onto the back stack. Without the [sidebar], only
 * the route's section shows. Home's sections, e.g. Routines, are pages of their own with an Agent picker.
 */
@Composable
fun SettingsContent(ui: SettingsUi, actions: SettingsActions, sidebar: Boolean = true) {
    // Only a page of Home with the shell beside it; elsewhere, e.g. during onboarding, it's a Settings page like the rest.
    val home = ui.route.section?.home == true && LocalHomeChrome.current != null
    val scope = if (home) AgentScope(ui.agents.filter { !it.external }, ui.route.agentId) { id -> id?.let { actions.onNavigate(Route.Settings(it, ui.route.section), false) } } else null
    CompositionLocalProvider(LocalAgentScope provides scope) {
        BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
            val wide = sidebar && !home && maxWidth >= 720.dp
            val split = maxWidth >= if (home) 720.dp else 1080.dp
            // Beside the sections, moving around replaces the screen; narrower, each level is pushed to go back from.
            val go = { route: Route.Settings -> actions.onNavigate(route, !wide) }
            val section = ui.route.section ?: SettingsSection.entries.firstOrNull { it.listed && ui.available(it) }?.takeIf { wide }
            val sections: @Composable () -> Unit = { SettingsSections(ui, section, go, wide) }
            val content: @Composable (SettingsSection) -> Unit = {
                SectionPane(it, ui, split, wide, go, actions, actions.onBack.takeIf { !wide && !home && LocalInteractionMode.current == InteractionMode.Touch })
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                // Rounded into the rail like the shell's column it stands in for.
                Box(Modifier.width(260.dp).fillMaxHeight().clip(RoundedCornerShape(topStart = Radius.large)).background(color(sidebarColor))) { sections() }
                VerticalSeparator()
                Box(Modifier.weight(1f)) { if (section != null) content(section) }
            } else if (section == null) sections()
            else content(section)
        }
    }
}

/** The Agent a Home page such as Routines shows, picked in its header; null in Settings. */
internal val LocalAgentScope = staticCompositionLocalOf<AgentScope?> { null }

@Composable
private fun SettingsSections(ui: SettingsUi, selected: SettingsSection?, go: (Route.Settings) -> Unit, wide: Boolean) {
    // Agents are set up from their own settings, and Routines and Tools from Home.
    val listed = SettingsSection.entries.filter { it.listed && !it.home }
    val groups = listOf("Device" to listed.filter { !it.server }, "Server" to listed.filter { it.server })
    val open = { section: SettingsSection -> go(ui.route.copy(section = section, item = null, editing = false)) }
    val uri = LocalUriHandler.current
    val links = listOf(Triple("Privacy policy", Lucide.ShieldCheck, PRIVACY_URL), Triple("Support", Lucide.LifeBuoy, SUPPORT_URL))
    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader("Settings")
        if (wide) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.space8, end = Spacing.space8, bottom = Spacing.space8)) {
            groups.forEachIndexed { i, (title, sections) ->
                SectionLabel(title, Modifier.padding(top = if (i > 0) Spacing.space12 else Spacing.none))
                sections.forEach { section ->
                    ListRow(selected = section == selected, onClick = { open(section) }, enabled = ui.available(section), leading = { Icon(section.icon(), Modifier.size(16.dp)) }) {
                        Text(section.title, Modifier.weight(1f), singleLine = true)
                        section.count(ui)?.let { Text("$it", style = TextStyles.caption, color = color(mutedColor)) }
                    }
                }
            }
            SectionLabel("About", Modifier.padding(top = 12.dp))
            links.forEach { (label, icon, url) ->
                ListRow(selected = false, onClick = { runCatching { uri.openUri(url) } }, leading = { Icon(icon, Modifier.size(16.dp)) }) {
                    Text(label, Modifier.weight(1f), singleLine = true)
                    Icon(Lucide.ExternalLink, Modifier.size(14.dp), tint = color(mutedColor))
                }
            }
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.space16), verticalArrangement = Arrangement.spacedBy(Spacing.space20)) {
            groups.forEach { (title, sections) ->
                RowGroup(title) {
                    sections.forEach { section ->
                        GroupRow(section.title, { open(section) }, icon = section.icon(), value = section.count(ui)?.toString(), enabled = ui.available(section))
                    }
                }
            }
            RowGroup("About") {
                links.forEach { (label, icon, url) -> ActionRow(label, icon, { runCatching { uri.openUri(url) } }) }
            }
        }
    }
}

private const val PRIVACY_URL = "https://kinuvo.feernandooff.com/privacy/"
private const val SUPPORT_URL = "https://kinuvo.feernandooff.com/support/"

internal fun SettingsSection.icon(): ImageVector = when (this) {
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
    SettingsSection.Providers, SettingsSection.Images -> Lucide.Sparkles
    SettingsSection.Harnesses -> Lucide.SquareTerminal
    SettingsSection.Hub -> Lucide.Wrench
    SettingsSection.Secrets -> Lucide.KeyRound
    SettingsSection.Search -> Lucide.Search
    SettingsSection.Notifications -> Lucide.Bell
    SettingsSection.LocalVoice -> Lucide.Mic
    SettingsSection.DeviceTools -> Lucide.Plug
}

private fun SettingsSection.count(ui: SettingsUi): Int? = when (this) {
    SettingsSection.Overview, SettingsSection.Agent, SettingsSection.Workspace, SettingsSection.Tools, SettingsSection.DeviceTools, SettingsSection.LocalVoice -> null
    SettingsSection.Routines -> ui.state.routines?.count { it.enabled }
    SettingsSection.Skills -> ui.config.skills?.count { it.active }
    SettingsSection.Mcp -> ui.config.mcp?.count { it.active }
    SettingsSection.Connection, SettingsSection.Images, SettingsSection.Providers, SettingsSection.Harnesses, SettingsSection.Hub, SettingsSection.Tasks, SettingsSection.Knowledge, SettingsSection.Secrets, SettingsSection.Search, SettingsSection.Notifications -> null
}

@Composable
private fun SectionPane(
    section: SettingsSection, ui: SettingsUi, split: Boolean, wide: Boolean, go: (Route.Settings) -> Unit, actions: SettingsActions,
    /** Leaves the section's first level: the way out of the sheet, or null beside the sections and on Home's pages. */
    rootBack: (() -> Unit)?,
) {
    val route = ui.route
    if (section == SettingsSection.LocalVoice) return DetailPane(rootBack, "Voice") { ServerVoiceSettings() }
    if (section == SettingsSection.DeviceTools) return DeviceToolsSettingsPane(Pane(ui, actions, go, rootBack))
    if (section == SettingsSection.Overview) return AgentContent(ui, { actions.onNavigate(it, true) }, rootBack, actions.onDeleteAgent)
    if (ui.capabilities != null && !ui.available(section)) return DetailPane(rootBack, section.title) { Text(UNAVAILABLE, color = color(mutedColor)) }
    if (section == SettingsSection.Agent || section == SettingsSection.Workspace || section == SettingsSection.Tools) {
        val pane = Pane(ui, actions, go, rootBack)
        return if (route.editing && section == SettingsSection.Agent) NewAgentForm(pane) else AgentPane(pane, section)
    }
    if (section.server && section != SettingsSection.Hub) {
        val pane = Pane(ui, actions, go, rootBack)
        return when (section) {
            SettingsSection.Connection -> ConnectionSettingsPane(pane)
            SettingsSection.Providers -> ProvidersSettingsPane(pane)
            SettingsSection.Images -> ImageGenerationSettingsPane(pane)
            SettingsSection.Harnesses -> HarnessesPane(pane)
            SettingsSection.Tasks -> TasksSettingsPane(pane)
            SettingsSection.Knowledge -> KnowledgeSettingsPane(pane)
            SettingsSection.Search -> ServiceSettingsPane(pane, ServiceKind.Search)
            SettingsSection.Notifications -> ServiceSettingsPane(pane, ServiceKind.Notifications)
            else -> SecretsSettingsPane(pane)
        }
    }
    val open = route.item != null || route.editing
    // From a form back to its item, from an item back to the list.
    val up = actions.onBack.takeIf { LocalInteractionMode.current == InteractionMode.Touch }
    val list: @Composable (Pane) -> Unit = { p ->
        when (section) {
            SettingsSection.Routines -> RoutineList(p)
            SettingsSection.Hub -> ToolList(p)
            SettingsSection.Skills -> SkillList(p)
            else -> McpList(p)
        }
    }
    val detail: @Composable (Pane) -> Unit = { p ->
        when (section) {
            SettingsSection.Routines -> RoutinePane(p)
            SettingsSection.Hub -> ToolPane(p)
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
    SettingsSection.Hub -> "a set of tools"
    SettingsSection.Connection, SettingsSection.Images, SettingsSection.Providers, SettingsSection.Harnesses, SettingsSection.Tasks, SettingsSection.Knowledge, SettingsSection.Secrets, SettingsSection.Search, SettingsSection.Notifications, SettingsSection.DeviceTools, SettingsSection.LocalVoice -> "a setting"
}
@Composable
private fun NothingSelected(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color(mutedColor))
    }
}

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
