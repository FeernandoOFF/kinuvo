package kinuvo.app.agent

import kinuvo.app.connection.*

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One Agent's settings, Skills and MCP servers; null until loaded. */
data class AgentConfig(val details: AgentDetails? = null, val skills: List<SkillInfo>? = null, val mcp: List<McpServerInfo>? = null)

/** The connected Server's [AgentConfig]s, reloaded after every change made here. */
class ConfigRepository(internal val connection: ConnectedServer) {
    /** By Agent ID. */
    val state: StateFlow<Map<String, AgentConfig>>
        field = MutableStateFlow(emptyMap())

    /** Whether a Server is connected now; screens load what they need when it turns true. */
    val connected: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The Server's model providers and their sign-in, null until loaded. */
    val providers: StateFlow<List<ProviderInfo>?>
        field = MutableStateFlow(null)

    private val scope get() = connection.scope
    private val client get() = connection.client
    private var attached: Job? = null
    private val revisions = mutableMapOf<String, Long>()

    internal fun attach(client: KinuvoClient) {
        clear()
        attached = scope.launch {
            launch { client.events(Api.ProvidersChanged).collect { providers.value = it } }
            client.state.collect {
                connected.value = it is ConnectionState.Connected
                if (it is ConnectionState.Connected) {
                    state.value.keys.forEach(::load)
                    catching { client.call(Api.Providers, Unit) }.onSuccess { providers.value = it }
                }
            }
        }
    }

    internal fun detach() { clear() }

    /** Also reloads [agentId] after every reconnect, even if this first load fails. */
    fun load(agentId: String) = scope.launch {
        edit(agentId) { it }
        loadDetails(agentId).join()
        loadSkills(agentId)
        loadMcp(agentId)
    }

    fun loadDetails(agentId: String) = scope.launch {
        edit(agentId) { it }
        val revision = revise(agentId)
        catching { call { it.call(Api.Agent, AgentRef(agentId)) } }.onSuccess { details ->
            if (revisions[agentId] == revision) edit(agentId) { it.copy(details = details) }
        }
    }

    suspend fun details(agentId: String) = call { it.call(Api.Agent, AgentRef(agentId)) }

    suspend fun providerAccounts() = call { it.call(Api.Providers, Unit) }

    suspend fun models(refresh: Boolean = false) = call { it.call(Api.Models, ModelsParams(refresh)) }

    suspend fun createAgent(params: CreateAgentParams) = call { it.call(Api.CreateAgent, params) }

    suspend fun updateAgent(params: UpdateAgentParams) = connection.run {
        val details = call { it.call(Api.UpdateAgent, params) }
        revise(params.agentId)
        edit(params.agentId) { it.copy(details = details) }
    }

    suspend fun deleteAgent(agentId: String) = connection.run {
        call { it.call(Api.DeleteAgent, AgentRef(agentId)) }
        state.update { it - agentId }
    }

    suspend fun skill(agentId: String, name: String, scope: ConfigScope?) = call { it.call(Api.Skill, SkillParams(agentId, name, scope)) }

    suspend fun saveSkill(params: SaveSkillParams): SkillInfo = connection.run { call { it.call(Api.SaveSkill, params) }.also { loadSkills(params.agentId) } }

    suspend fun deleteSkill(params: SkillParams) = connection.run {
        call { it.call(Api.DeleteSkill, params) }
        loadSkills(params.agentId)
    }

    suspend fun saveMcp(params: SaveMcpParams): McpServerInfo = connection.run { call { it.call(Api.SaveMcp, params) }.also { loadMcp(params.agentId) } }

    suspend fun removeMcp(params: McpParams) = connection.run {
        call { it.call(Api.RemoveMcp, params) }
        loadMcp(params.agentId)
    }

    suspend fun testMcp(params: McpParams) = connection.run { call { it.call(Api.TestMcp, params) }.also { if (it.needsLogin) loadMcp(params.agentId) } }

    suspend fun loginMcp(params: McpLoginParams) = call { it.call(Api.LoginMcp, params) }

    suspend fun completeMcpLogin(agentId: String, callbackUrl: String) = connection.run {
        call { it.call(Api.CompleteMcpLogin, McpLoginCompleteParams(callbackUrl)) }
        loadMcp(agentId)
    }

    suspend fun logoutMcp(params: McpParams) = connection.run {
        call { it.call(Api.LogoutMcp, params) }
        loadMcp(params.agentId)
    }

    suspend fun tasksSetup() = call { it.call(Api.TasksSettings, Unit) }

    suspend fun saveTasks(settings: TasksSettings) = call { it.call(Api.SaveTasksSettings, settings) }

    suspend fun providerLists(provider: String) = call { it.call(Api.ProviderLists, TaskProviderRef(provider)) }
    suspend fun authorizeTasks(provider: String) = call { it.call(Api.AuthorizeTasks, TaskProviderRef(provider)) }

    suspend fun knowledgeSetup() = call { it.call(Api.KnowledgeSettings, Unit) }

    suspend fun saveKnowledge(settings: KnowledgeSettings) = call { it.call(Api.SaveKnowledgeSettings, settings) }
    suspend fun testKnowledge(settings: KnowledgeSettings) = call { it.call(Api.TestKnowledge, settings) }

    suspend fun service(kind: ServiceKind) = call { it.call(Api.Service, ServiceRef(kind)) }
    suspend fun saveService(params: SaveServiceParams) = call { it.call(Api.SaveService, params) }
    suspend fun testService(params: TestServiceParams) = call { it.call(Api.TestService, params) }

    suspend fun loginProvider(id: String) = call { it.call(Api.LoginProvider, ProviderRef(id)) }
    suspend fun saveProviderApiKey(id: String, value: String) = call { it.call(Api.SetProviderApiKey, SetProviderApiKeyParams(id, value)) }
    suspend fun logoutProvider(id: String) = call { it.call(Api.LogoutProvider, ProviderRef(id)) }

    suspend fun secretProviders() = call { it.call(Api.SecretProviders, Unit) }
    suspend fun connectSecrets(scheme: String, token: String) = call { it.call(Api.ConnectSecrets, ConnectSecretsParams(scheme, token)) }
    suspend fun disconnectSecrets(scheme: String) = call { it.call(Api.DisconnectSecrets, SecretProviderRef(scheme)) }
    suspend fun browseSecrets(scheme: String, path: List<String>) = call { it.call(Api.BrowseSecrets, BrowseSecretsParams(scheme, path)) }
    suspend fun refreshSecrets() = call { it.call(Api.RefreshSecrets, Unit) }

    private suspend fun loadSkills(agentId: String) {
        catching { call { it.call(Api.Skills, AgentRef(agentId)) } }.onSuccess { s -> edit(agentId) { it.copy(skills = s) } }
    }

    private suspend fun loadMcp(agentId: String) {
        catching { call { it.call(Api.McpServers, AgentRef(agentId)) } }.onSuccess { m -> edit(agentId) { it.copy(mcp = m) } }
    }

    private fun revise(agentId: String): Long = ((revisions[agentId] ?: 0) + 1).also { revisions[agentId] = it }

    private fun edit(agentId: String, change: (AgentConfig) -> AgentConfig) = state.update { it + (agentId to change(it[agentId] ?: AgentConfig())) }

    private suspend fun <T> call(block: suspend (KinuvoClient) -> T): T =
        connection.call(block)

    private fun clear() {
        connected.value = false
        attached?.cancel()
        state.value = emptyMap()
        revisions.clear()
        providers.value = null
    }
}
