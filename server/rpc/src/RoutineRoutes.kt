package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.routineRoutes() {
    handle(Api.Routines) { server.routines.list(it.agentId) }
    handle(Api.CreateRoutine) { server.routines.create(it) }
    handle(Api.UpdateRoutine) { server.routines.update(it) }
    handle(Api.RunRoutine) { server.routines.run(it) }
    handle(Api.RemoveRoutine) { server.routines.remove(it) }
}
