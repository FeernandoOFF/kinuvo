package kinuvo.server.rpc

import kinuvo.protocol.Api
import kinuvo.protocol.artifact.ArtifactState

internal fun RouteBuilder.artifactRoutes() {
    device(Api.Artifacts) { server.artifacts.list() }
    device(Api.Artifact) { server.artifacts.get(it.id) }
    device(Api.ArtifactFileList) { server.artifacts.files(it.id) }
    device(Api.ArtifactFileRead) { server.artifacts.readFile(it.id, it.path, it.offset, it.length) }
    handle(Api.SaveArtifact) { server.artifacts.replace(it.id, it.content) }
    handle(Api.DeleteArtifact) { server.artifacts.delete(it.id) }
    // Ordered, so a presenter's quick taps reach the other Devices in the order they were made.
    device(Api.WatchArtifactState, ordered = true) {
        server.artifacts.get(it.id)
        ArtifactState(it.id, server.artifacts.states.watch(it.id, caller) { change -> caller.offer(Api.ArtifactStateChanged, change) })
    }
    device(Api.UnwatchArtifactState, ordered = true) { server.artifacts.states.unwatch(it.id, caller) }
    device(Api.SetArtifactState, ordered = true) {
        check(server.artifacts.states.watching(it.id, caller)) { "Watch the artifact's state before setting it" }
        server.artifacts.states.set(it.id, caller, it.state)
    }
}
