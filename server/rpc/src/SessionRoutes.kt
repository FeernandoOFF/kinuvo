package kinuvo.server.rpc

import kinuvo.protocol.Api
import kinuvo.server.MAX_PAGE

internal fun RouteBuilder.sessionRoutes() {
    device(Api.Sessions) { server.harness.log.page(it.agentId, it.before?.value, it.limit.coerceIn(1, MAX_PAGE), it.routineId) }
    device(Api.Session) { server.harness.log.session(it.agentId, it.sessionId) }
    device(Api.CatchUp) { server.harness.log.subscribe(it.agentId, it.sessionId, it.after, it.limit.coerceIn(1, MAX_PAGE), caller.subscriber) }
    device(Api.Unsubscribe) { server.harness.log.unsubscribe(it.sessionId, caller.subscriber) }
    device(Api.History) { server.harness.log.history(it.agentId, it.sessionId, it.before, it.limit.coerceIn(1, MAX_PAGE)) }
    device(Api.CreateSession) { server.dispatch.create(it.agentId) }
    handle(Api.ConfigureSession) { server.sessions.configure(it).also { server.capabilitiesChanged() } }
    device(Api.UpdateSession) { server.sessions.update(it) }
    device(Api.Capabilities) { server.execution.sessionCapabilities(it) }
    device(Api.Send) { server.dispatch.send(it, caller.surfaceCatalogs) }
    device(Api.Stop) { server.dispatch.stop(it.sessionId) }
    device(Api.UploadAttachment) { server.uploadAttachment(it) }
    device(Api.Attachment) { server.downloadAttachment(it) }
    handle(Api.ReviewReflection) { server.reflection.respond(server.admin.agent(it.agentId), it.sessionId, it.proposalId, it.approve, it.note) }
    device(Api.Respond) { server.execution.respond(it, caller.management) }
}
