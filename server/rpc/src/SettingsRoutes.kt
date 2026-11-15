package kinuvo.server.rpc

import kinuvo.protocol.Api
import kinuvo.protocol.service.ServiceKind

internal fun RouteBuilder.settingsRoutes() {
    handle(Api.SecretProviders) { server.secrets.providers.map { p -> p.status() } }
    handle(Api.ConnectSecrets) { p -> server.secrets.provider(p.scheme).run { connect(p.token); server.secrets.refresh(); status() } }
    handle(Api.DisconnectSecrets) { p -> server.secrets.provider(p.scheme).run { disconnect(); server.secrets.refresh(); status() } }
    handle(Api.BrowseSecrets) { server.secrets.provider(it.scheme).browse(it.path) }
    handle(Api.RefreshSecrets) { server.secrets.refresh() }
    handle(Api.Service) { server.settings.serviceSetup(it.kind) }
    handle(Api.SaveService) { server.settings.saveService(it.kind, it.settings, it.secretUpdates) }
    handle(Api.TestService) { if (it.kind == ServiceKind.Notifications) server.notifications.test(it) else server.settings.testService(it) }
}
