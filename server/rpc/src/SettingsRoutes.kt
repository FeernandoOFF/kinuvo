package kinuvo.server.rpc

import kinuvo.protocol.Api
import kinuvo.protocol.service.ServiceKind

internal fun RouteBuilder.settingsRoutes() {
    handle(Api.SecretProviders) { server.harness.secrets.providers.map { p -> p.status() } }
    handle(Api.ConnectSecrets) { p -> server.harness.secrets.provider(p.scheme).run { connect(p.token); server.harness.secrets.refresh(); status() } }
    handle(Api.DisconnectSecrets) { p -> server.harness.secrets.provider(p.scheme).run { disconnect(); server.harness.secrets.refresh(); status() } }
    handle(Api.BrowseSecrets) { server.harness.secrets.provider(it.scheme).browse(it.path) }
    handle(Api.RefreshSecrets) { server.harness.secrets.refresh() }
    handle(Api.Service) { server.harness.serviceSetup(it.kind) }
    handle(Api.SaveService) { server.harness.saveService(it.kind, it.settings, it.secretUpdates) }
    handle(Api.TestService) { if (it.kind == ServiceKind.Notifications) server.notifications.test(it) else server.harness.testService(it) }
}
