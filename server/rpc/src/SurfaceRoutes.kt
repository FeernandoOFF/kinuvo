package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.surfaceRoutes() {
    device(Api.SurfaceCatalogs, ordered = true) { caller.setSurfaceCatalogs(it.catalogs) }
    device(Api.SurfaceImage) { server.surfaceImage(it.url) }
    device(Api.AppResource) { server.appResource(it) }
    device(Api.AppCallTool) { server.appCallTool(server.admin.agent(it.app.agentId), it) }
}
