package kinuvo.server.rpc

import kinuvo.server.Routes

/** Every route of a Server, grouped by feature. */
fun serverRoutes(): Routes = routes {
    sessionRoutes()
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
    mcpRoutes()
    settingsRoutes()
    providerRoutes()
    deviceRoutes()
    pushRoutes()
    artifactRoutes()
    surfaceRoutes()
}
