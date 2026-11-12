package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.artifactRoutes() {
    device(Api.Artifacts) { server.harness.artifacts.list() }
    device(Api.Artifact) { server.harness.artifacts.get(it.id) }
    handle(Api.SaveArtifact) { server.harness.artifacts.replace(it.id, it.content) }
    handle(Api.DeleteArtifact) { server.harness.artifacts.delete(it.id) }
}
