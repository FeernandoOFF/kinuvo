package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.artifactRoutes() {
    device(Api.Artifacts) { server.artifacts.list() }
    device(Api.Artifact) { server.artifacts.get(it.id) }
    handle(Api.SaveArtifact) { server.artifacts.replace(it.id, it.content) }
    handle(Api.DeleteArtifact) { server.artifacts.delete(it.id) }
}
