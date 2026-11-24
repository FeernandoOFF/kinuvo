package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.providerRoutes() {
    device(Api.Models) { server.modelCatalog(it.refresh) }
    handle(Api.DiscoverEndpoint) { server.customEndpoints().discover(it) }
    handle(Api.SaveEndpoint) { server.customEndpoints().save(it).also { server.logins.refresh() } }
    handle(Api.Providers) { server.logins.list() }
    handle(Api.SetProviderApiKey) { server.logins.saveApiKey(it.provider, it.value) }
    handle(Api.SignInProvider) { server.logins.signIn(it.provider, it.redirectUri, it.anotherAccount, it.requestConsent) }
    handle(Api.CancelProviderSignIn) { server.logins.cancelSignIn(it.provider) }
    handle(Api.CompleteProviderSignIn) { server.logins.completeSignIn(it.provider, it.callbackUrl) }
    handle(Api.LoginProvider) { server.logins.login(it.provider) }
    handle(Api.LogoutProvider) { server.logins.logout(it.provider) }
    handle(Api.DownloadProviderModel) { server.logins.download(it.provider, it.model) }
    handle(Api.CancelProviderDownload) { server.logins.cancelDownload(it.provider) }
    handle(Api.RemoveProviderModel) { server.logins.removeModel(it.provider, it.model) }
}
