package kinuvo.harness.server

import kinuvo.harness.core.Agent
import kinuvo.protocol.*

/** The Server's settings: Secret reference providers, services, model provider sign-ins and MCP servers. */
internal class SettingsRequests(private val harness: HarnessRuntime, private val logins: ProviderLogins, private val agent: suspend (String) -> Agent) {
    fun <C> register(handlers: RequestHandlers<C>) = with(handlers) {
        on(Api.SecretProviders) { harness.secrets.providers.map { it.status() } }
        on(Api.ConnectSecrets) { p -> harness.secrets.provider(p.scheme).run { connect(p.token); harness.secrets.refresh(); status() } }
        on(Api.DisconnectSecrets) { p -> harness.secrets.provider(p.scheme).run { disconnect(); harness.secrets.refresh(); status() } }
        on(Api.BrowseSecrets) { harness.secrets.provider(it.scheme).browse(it.path) }
        on(Api.RefreshSecrets) { harness.secrets.refresh() }
        on(Api.Service) { harness.serviceSetup(it.kind) }
        on(Api.SaveService) { harness.saveService(it.kind, it.settings, it.secretUpdates) }
        on(Api.TestService) { harness.testService(it) }
        on(Api.Models) { harness.modelCatalog(it.refresh) }
        on(Api.Providers) { logins.list() }
        on(Api.SetProviderApiKey) { logins.saveApiKey(it.provider, it.value) }
        on(Api.LoginProvider) { logins.login(it.provider) }
        on(Api.LogoutProvider) { logins.logout(it.provider) }
        on(Api.DownloadProviderModel) { logins.download(it.provider, it.model) }
        on(Api.CancelProviderDownload) { logins.cancelDownload(it.provider) }
        on(Api.RemoveProviderModel) { logins.removeModel(it.provider, it.model) }
        on(Api.McpServers) { harness.mcp.mcpServers(agent(it.agentId.value)) }
        on(Api.SaveMcp) { harness.mcp.saveMcp(it) }
        on(Api.RemoveMcp) { harness.mcp.removeMcp(it) }
        on(Api.TestMcp) { harness.mcp.testMcp(it) }
        on(Api.LoginMcp) { harness.mcp.loginMcp(it) }
        on(Api.CompleteMcpLogin) { harness.mcp.completeMcpLogin(it) }
        on(Api.LogoutMcp) { harness.mcp.logoutMcp(it) }
    }
}
