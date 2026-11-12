package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.teamRoutes() {
    device(Api.Teams) { server.teams.list() }
    device(Api.Team) { server.teams.get(it) }
    device(Api.CreateTeam) { server.teams.create(it) }
    device(Api.UpdateTeam) { server.teams.update(it) }
    device(Api.DeleteTeam) { server.teams.delete(it) }
    device(Api.TeamSend) { server.teams.send(it) }
    device(Api.TeamStop) { server.teams.stop(it) }
    device(Api.TeamRespond) { server.teams.respond(it) }
}
