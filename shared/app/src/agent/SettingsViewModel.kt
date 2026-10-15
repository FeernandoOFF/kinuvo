package kinuvo.app.agent

import kinuvo.app.connection.*
import kinuvo.app.routine.*
import kinuvo.app.navigation.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * [routines] is null until they first load; [runs] are the open Routine's latest Sessions, [skill] the open Skill and
 * [mcpTest] the last test of the open MCP server.
 */
data class SettingsState(
    val route: Route.Settings,
    val config: AgentConfig = AgentConfig(),
    val routines: List<Routine>? = null,
    val runs: List<SessionInfo> = emptyList(),
    val skill: SkillDetails? = null,
    val mcpTest: McpTestResult? = null,
    val testing: Boolean = false,
    /** The MCP sign-in page open in a browser, until it comes back or its URL is pasted. */
    val signInUrl: String? = null,
    val tasks: TasksSetup? = null,
    /** The lists of each task provider, as loaded for the Tasks section. */
    val providerLists: Map<String, List<TaskListInfo>> = emptyMap(),
    val knowledge: KnowledgeSetup? = null,
    /** The last test of the Knowledge settings form. */
    val knowledgeTest: KnowledgeTestResult? = null,
    val providers: List<ProviderInfo>? = null,
    /** The device code of the provider sign-in in progress, by provider. */
    val deviceCode: Pair<String, DeviceCode>? = null,
    /** The Server's password managers, null until loaded. */
    val secrets: List<SecretProviderStatus>? = null,
    /** Names under each browsed path (scheme first, then vault and item), for picking a Secret reference. */
    val secretEntries: Map<List<String>, List<SecretEntry>> = emptyMap(),
    /** Web search and notification settings, as loaded for their sections. */
    val services: Map<ServiceKind, ServiceSetup> = emptyMap(),
    /** The last test of a service's settings form. */
    val serviceTest: ServiceTestResult? = null,
    val models: ModelsCatalog = ModelsCatalog(),
    val saved: Boolean = false,
    val errors: List<String> = emptyList(),
    /** The saved Server open in Connection, by key; null for the connected one. */
    val server: String? = route.item.takeIf { route.section == SettingsSection.Connection },
) {
    val routine get() = routines?.firstOrNull { it.id == route.item }
}

/** A Skill's or MCP server's [Route.Settings.item]; the same name can be the Agent's and shared. */
fun itemKey(scope: ConfigScope, name: String) = "${scope.name}/$name"

fun parseItem(item: String?): Pair<ConfigScope, String>? {
    val (scope, name) = item?.split("/", limit = 2)?.takeIf { it.size == 2 } ?: return null
    return ConfigScope.entries.firstOrNull { it.name == scope }?.let { it to name }
}

/** One Agent's settings screen: its settings, Routines, Skills and MCP servers, kept current by the repositories. */
class SettingsViewModel(
    private val routinesRepo: RoutineRepository,
    private val config: ConfigRepository,
    private val browser: OAuthBrowser,
    route: Route.Settings,
) : ViewModel() {
    val state: StateFlow<SettingsState>
        field = MutableStateFlow(SettingsState(route))
    private val agentId = route.agentId
    private var listsJob: Job? = null
    private var signInJob: Job? = null

    fun pickServer(key: String) = state.update { it.copy(server = key) }

    init {
        config.connection.bind(viewModelScope)
        routinesRepo.load(agentId)
        config.load(agentId)
        viewModelScope.launch { config.connected.collect { if (it) loadModels() } }
        viewModelScope.launch {
            config.providers.collect { p ->
                state.update { s -> s.copy(providers = p, deviceCode = s.deviceCode?.takeIf { (id) -> p?.firstOrNull { it.id == id }?.let { it.account?.signedIn != true && it.error == null } == true }) }
                if (p != null) loadModels()
            }
        }
        viewModelScope.launch { routinesRepo.state.map { it[agentId] }.distinctUntilChanged().collect { r -> state.update { it.copy(routines = r) } } }
        viewModelScope.launch { config.state.map { it[agentId] ?: AgentConfig() }.distinctUntilChanged().collect { c -> state.update { it.copy(config = c) } } }
        viewModelScope.launch {
            state.map { it.routine?.let { r -> Triple(r.id, r.lastRunAt, r.lastStatus) } }.distinctUntilChanged().collect { key ->
                val runs = key?.let { (id) -> catching { routinesRepo.runs(agentId, id) }.getOrNull() }.orEmpty()
                state.update { it.copy(runs = runs) }
            }
        }
        // Server-wide settings load once connected, and again after a reconnect if they hadn't loaded.
        if (route.section?.server == true) viewModelScope.launch {
            config.connected.collect { if (it && state.value.tasks == null && state.value.knowledge == null && state.value.secrets == null && state.value.services.isEmpty()) reloadServer() }
        }
        val skill = parseItem(route.item).takeIf { route.section == SettingsSection.Skills }
        if (skill != null) viewModelScope.launch {
            state.map { it.config.skills }.distinctUntilChanged().collect {
                val details = catching { config.skill(agentId, skill.second, skill.first) }.getOrNull()
                state.update { it.copy(skill = details) }
            }
        }
    }

    fun loadModels(refresh: Boolean = false) = attempt {
        val models = config.models(refresh)
        state.update { it.copy(models = models) }
    }

    fun saveAgent(params: UpdateAgentParams) = attempt {
        config.updateAgent(params)
        state.update { it.copy(saved = true) }
    }

    fun createAgent(params: CreateAgentParams, onCreated: (String) -> Unit) = attempt { onCreated(config.createAgent(params).id) }

    fun deleteAgent(onDeleted: () -> Unit) = attempt {
        config.deleteAgent(agentId)
        onDeleted()
    }

    fun setEnabled(routineId: String, enabled: Boolean) = attempt { routinesRepo.update(agentId, routineId, RoutineSpec(enabled = enabled)) }

    fun setApprovals(routineId: String, approvals: RoutineApprovals) = attempt { routinesRepo.update(agentId, routineId, RoutineSpec(approvals = approvals)) }

    /** Creates a Routine when [routineId] is null. */
    fun saveRoutine(routineId: String?, spec: RoutineSpec, onSaved: (String) -> Unit) = attempt {
        onSaved((if (routineId == null) routinesRepo.create(agentId, spec) else routinesRepo.update(agentId, routineId, spec)).id)
    }

    fun runNow(routineId: String) = attempt { routinesRepo.run(agentId, routineId) }

    fun remove(routineId: String, onRemoved: () -> Unit) = attempt {
        routinesRepo.remove(agentId, routineId)
        onRemoved()
    }

    fun saveSkill(scope: ConfigScope, name: String, content: String, onSaved: (String) -> Unit) = attempt {
        val saved = config.saveSkill(SaveSkillParams(agentId, name, content, scope))
        onSaved(itemKey(saved.scope, saved.name))
    }

    fun deleteSkill(scope: ConfigScope, name: String, onDeleted: () -> Unit) = attempt {
        config.deleteSkill(SkillParams(agentId, name, scope))
        onDeleted()
    }

    fun saveMcp(server: McpServerInfo, onSaved: (String) -> Unit) = attempt {
        val saved = config.saveMcp(SaveMcpParams(agentId, server))
        onSaved(itemKey(saved.scope, saved.name))
    }

    fun removeMcp(scope: ConfigScope, name: String, onRemoved: () -> Unit) = attempt {
        config.removeMcp(McpParams(agentId, name, scope))
        onRemoved()
    }

    fun testMcp(scope: ConfigScope, name: String) = attempt {
        state.update { it.copy(testing = true, mcpTest = null) }
        try {
            val result = config.testMcp(McpParams(agentId, name, scope))
            state.update { it.copy(mcpTest = result) }
        } finally {
            if (config.connection.isCurrent()) state.update { it.copy(testing = false) }
        }
    }

    /** Opens the server's sign-in page; a browser that can't come back (e.g. the port is busy) leaves pasting its URL. */
    fun signInMcp(scope: ConfigScope, name: String) {
        signInJob?.cancel()
        signInJob = attempt {
            val login = config.loginMcp(McpLoginParams(agentId, name, scope, browser.redirect))
            state.update { it.copy(signInUrl = login.url, mcpTest = null) }
            val returned = browser.signIn(login.url)
            state.update { it.copy(signInUrl = null) }
            returned?.let { config.completeMcpLogin(agentId, it) }
        }
    }

    /** Finishes a sign-in with the URL the browser ended on. */
    fun completeMcpSignIn(callbackUrl: String) {
        signInJob?.cancel()
        attempt {
            config.completeMcpLogin(agentId, callbackUrl)
            state.update { it.copy(signInUrl = null) }
        }
    }

    fun cancelMcpSignIn() {
        signInJob?.cancel()
        state.update { it.copy(signInUrl = null) }
    }

    fun signOutMcp(scope: ConfigScope, name: String) = attempt { config.logoutMcp(McpParams(agentId, name, scope)) }

    /** Loads the open server-wide section's settings, e.g. to retry after a failure. */
    fun reloadServer() {
        when (state.value.route.section) {
            SettingsSection.Tasks -> attempt { config.tasksSetup().also { s -> state.update { it.copy(tasks = s) }; loadLists(s.settings.provider) } }
            SettingsSection.Knowledge -> attempt { config.knowledgeSetup().let { s -> state.update { it.copy(knowledge = s) } } }
            SettingsSection.Secrets -> loadSecrets()
            SettingsSection.Search -> loadService(ServiceKind.Search)
            SettingsSection.Notifications -> loadService(ServiceKind.Notifications)
            else -> Unit
        }
    }

    /** Loads [provider]'s lists once, e.g. when it's picked in the Tasks section. */
    fun loadLists(provider: String) {
        listsJob?.cancel()
        if (provider in state.value.providerLists) return
        listsJob = attempt { config.providerLists(provider).let { l -> state.update { it.copy(providerLists = it.providerLists + (provider to l), errors = emptyList()) } } }
    }

    fun authorizeTasks(provider: String) {
        listsJob?.cancel()
        listsJob = attempt {
            state.update { it.copy(testing = true) }
            try {
                val lists = config.authorizeTasks(provider)
                state.update { it.copy(providerLists = it.providerLists + (provider to lists), errors = emptyList()) }
            } finally {
                if (config.connection.isCurrent()) state.update { it.copy(testing = false) }
            }
        }
    }

    fun saveTasks(settings: TasksSettings) = attempt {
        val saved = config.saveTasks(settings)
        state.update { it.copy(tasks = saved, saved = true) }
    }

    fun saveKnowledge(settings: KnowledgeSettings) = attempt {
        val saved = config.saveKnowledge(settings)
        state.update { it.copy(knowledge = saved, saved = true) }
    }

    fun testKnowledge(settings: KnowledgeSettings) = attempt {
        state.update { it.copy(testing = true, knowledgeTest = null) }
        try {
            val result = config.testKnowledge(settings)
            state.update { it.copy(knowledgeTest = result) }
        } finally {
            if (config.connection.isCurrent()) state.update { it.copy(testing = false) }
        }
    }

    private fun loadService(kind: ServiceKind) = attempt { config.service(kind).let { s -> state.update { it.copy(services = it.services + (kind to s)) } } }

    fun saveService(params: SaveServiceParams) = attempt {
        val saved = config.saveService(params)
        state.update { it.copy(services = it.services + (params.kind to saved), saved = true) }
    }

    fun testService(params: TestServiceParams) = attempt {
        state.update { it.copy(testing = true, serviceTest = null) }
        try {
            val result = config.testService(params)
            state.update { it.copy(serviceTest = result) }
        } finally {
            if (config.connection.isCurrent()) state.update { it.copy(testing = false) }
        }
    }

    fun loginProvider(id: String) = attempt {
        state.update { it.copy(deviceCode = null) }
        val code = config.loginProvider(id)
        state.update { it.copy(deviceCode = id to code) }
    }

    fun saveProviderApiKey(id: String, value: String) = attempt {
        config.saveProviderApiKey(id, value)
        state.update { it.copy(saved = true) }
    }

    fun logoutProvider(id: String) = attempt { config.logoutProvider(id) }

    fun loadSecrets() = attempt { config.secretProviders().let { s -> state.update { it.copy(secrets = s) } } }

    fun connectSecrets(scheme: String, token: String) = attempt {
        state.update { it.copy(testing = true) }
        try {
            secretStatus(config.connectSecrets(scheme, token))
            state.update { it.copy(saved = true, secretEntries = emptyMap()) }
        } finally {
            if (config.connection.isCurrent()) state.update { it.copy(testing = false) }
        }
    }

    fun disconnectSecrets(scheme: String) = attempt {
        secretStatus(config.disconnectSecrets(scheme))
        state.update { it.copy(secretEntries = emptyMap()) }
    }

    fun refreshSecrets() = attempt {
        config.refreshSecrets()
        state.update { it.copy(saved = true, secretEntries = emptyMap()) }
    }

    /** Loads the names under [path] (scheme first) unless they're loaded. */
    fun browseSecrets(path: List<String>) {
        if (path in state.value.secretEntries) return
        attempt { config.browseSecrets(path.first(), path.drop(1)).let { e -> state.update { it.copy(secretEntries = it.secretEntries + (path to e)) } } }
    }

    private fun secretStatus(status: SecretProviderStatus) =
        state.update { s -> s.copy(secrets = s.secrets.orEmpty().filter { it.scheme != status.scheme } + status) }

    private fun attempt(block: suspend () -> Unit): Job {
        state.update { it.copy(errors = emptyList(), saved = false) }
        return viewModelScope.launch {
            try {
                config.connection.run { block() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(errors = it.errors + (e.message ?: e.toString())) }
            }
        }
    }
}
