package kinuvo.server.rpc

import kinuvo.server.Diagnostics
import kinuvo.protocol.Api
import kinuvo.server.Routes

/** Every route of a Server, grouped by feature. */
fun serverRoutes(diagnostics: Diagnostics = Diagnostics.Unavailable): Routes = routes {
    handle(Api.DiagnosticsStatus) { diagnostics.status() }
    handle(Api.DiagnosticsExport) { diagnostics.export() }
    handle(Api.DiagnosticsRecord) { diagnostics.record(it.minutes) }
    sessionRoutes()
    teamRoutes()
    agentRoutes()
    skillRoutes()
    executionRoutes()
    screenRoutes()
    browserRoutes()
    computerRoutes()
    voiceRoutes()
    knowledgeRoutes()
    boardRoutes()
    routineRoutes()
    workflowRoutes()
    mcpRoutes()
    settingsRoutes()
    providerRoutes()
    deviceRoutes()
    pushRoutes()
    artifactRoutes()
    surfaceRoutes()
}
