package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.skillRoutes() {
    device(Api.Skills) { server.admin.skills(server.admin.agent(it.agentId)) }
    device(Api.Skill) { server.admin.skill(server.admin.agent(it.agentId), it.name, it.scope) }
    handle(Api.SaveSkill) { server.admin.saveSkill(it) }
    handle(Api.DeleteSkill) { server.admin.deleteSkill(it) }
}
