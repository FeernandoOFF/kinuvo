package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.executionRoutes() {
    handle(Api.ProbeHarnessConnection) { server.execution.checkHarness(it) }
    handle(Api.ExecutionCatalog) { server.execution.catalog() }
    device(Api.ExecutionSnapshot) { server.execution.snapshot(it.id) }
    device(Api.ResumeExecution) { server.execution.resume(it.id) }
    device(Api.CloseExecution) { server.execution.closeExecution(it.id) }
    handle(Api.SaveHarnessConnection) { server.execution.saveConnection(it) }
    handle(Api.DeleteHarnessConnection) { server.execution.deleteConnection(it.id) }
    handle(Api.RegisterExternalAgent) { server.execution.registerAgent(it) }
    handle(Api.UnregisterExternalAgent) { server.execution.unregisterAgent(it.id) }
    handle(Api.GrantExecution) { server.execution.grant(it) }
    handle(Api.ExecutionGrants) { server.execution.grants() }
    handle(Api.RevokeExecution) { server.execution.revoke(it.id) }
    handle(Api.SaveRemoteLink) { server.execution.saveRemoteLink(it) }
    handle(Api.DiscoverRemote) { server.execution.discoverRemote(it.id) }
    linked(Api.RemoteExecution) { caller.remoteExecution(it) }
}
