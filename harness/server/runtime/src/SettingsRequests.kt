package kinuvo.harness.server

import kinuvo.harness.core.Agent
import kinuvo.protocol.*

/** The Server's settings: Secret reference providers, services, model provider sign-ins and MCP servers. */
internal class SettingsRequests(private val harness: HarnessRuntime, private val logins: ProviderLogins, private val agent: suspend (String) -> Agent, private val notifications: Notifications) {
    suspend fun handle(r: Frame.Request): Frame.Response? = when (r.method) {
        Api.SecretProviders.name -> respond(r, Api.SecretProviders) { harness.secrets.providers.map { it.status() } }
        Api.ConnectSecrets.name -> respond(r, Api.ConnectSecrets) { p -> harness.secrets.provider(p.scheme).run { connect(p.token); harness.secrets.refresh(); status() } }
        Api.DisconnectSecrets.name -> respond(r, Api.DisconnectSecrets) { p -> harness.secrets.provider(p.scheme).run { disconnect(); harness.secrets.refresh(); status() } }
        Api.BrowseSecrets.name -> respond(r, Api.BrowseSecrets) { harness.secrets.provider(it.scheme).browse(it.path) }
        Api.RefreshSecrets.name -> respond(r, Api.RefreshSecrets) { harness.secrets.refresh() }
        Api.Service.name -> respond(r, Api.Service) { harness.serviceSetup(it.kind) }
        Api.SaveService.name -> respond(r, Api.SaveService) { harness.saveService(it.kind, it.settings, it.secretUpdates) }
        Api.TestService.name -> respond(r, Api.TestService) { if (it.kind == ServiceKind.Notifications) notifications.test(it) else harness.testService(it) }
        Api.Models.name -> respond(r, Api.Models) { harness.modelCatalog(it.refresh) }
        Api.Providers.name -> respond(r, Api.Providers) { logins.list() }
        Api.SetProviderApiKey.name -> respond(r, Api.SetProviderApiKey) { logins.saveApiKey(it.provider, it.value) }
        Api.LoginProvider.name -> respond(r, Api.LoginProvider) { logins.login(it.provider) }
        Api.LogoutProvider.name -> respond(r, Api.LogoutProvider) { logins.logout(it.provider) }
        Api.DownloadProviderModel.name -> respond(r, Api.DownloadProviderModel) { logins.download(it.provider, it.model) }
        Api.CancelProviderDownload.name -> respond(r, Api.CancelProviderDownload) { logins.cancelDownload(it.provider) }
        Api.RemoveProviderModel.name -> respond(r, Api.RemoveProviderModel) { logins.removeModel(it.provider, it.model) }
        Api.McpServers.name -> respond(r, Api.McpServers) { harness.mcp.mcpServers(agent(it.agentId)) }
        Api.SaveMcp.name -> respond(r, Api.SaveMcp) { harness.mcp.saveMcp(it) }
        Api.RemoveMcp.name -> respond(r, Api.RemoveMcp) { harness.mcp.removeMcp(it) }
        Api.TestMcp.name -> respond(r, Api.TestMcp) { harness.mcp.testMcp(it) }
        Api.LoginMcp.name -> respond(r, Api.LoginMcp) { harness.mcp.loginMcp(it) }
        Api.CompleteMcpLogin.name -> respond(r, Api.CompleteMcpLogin) { harness.mcp.completeMcpLogin(it) }
        Api.LogoutMcp.name -> respond(r, Api.LogoutMcp) { harness.mcp.logoutMcp(it) }
        else -> null
    }
}
