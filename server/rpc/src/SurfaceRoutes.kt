package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.surfaceRoutes() {
    device(Api.SurfaceCatalogs) { caller.setSurfaceCatalogs(it.catalogs) }
    device(Api.SurfaceImage) { server.surfaceImage(it.url) }
    device(Api.AppResource) { server.harness.appResource(it) }
    device(Api.AppCallTool) { server.harness.appCallTool(server.admin.agent(it.app.agentId), it) }
}
