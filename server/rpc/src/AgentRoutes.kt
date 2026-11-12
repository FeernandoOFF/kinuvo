package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.agentRoutes() {
    device(Api.Agents) { server.execution.agents() }
    device(Api.Agent) { server.execution.agentDetails(it.agentId) }
    handle(Api.CreateAgent) { server.execution.createAgent(it) }
    handle(Api.UpdateAgent) { server.admin.update(it).also { server.capabilitiesChanged() } }
    handle(Api.DeleteAgent) { server.admin.delete(it.agentId.value) }
    handle(Api.TestExec) { server.admin.testExec(it) }
    handle(Api.ImageModel) { server.admin.imageModel() }
    handle(Api.SaveImageModel) { server.admin.saveImageModel(it).also { server.capabilitiesChanged() } }
    handle(Api.ServerRules) { server.admin.serverRules() }
    handle(Api.SaveServerRules) { server.admin.saveServerRules(it).also { server.capabilitiesChanged() } }
}
