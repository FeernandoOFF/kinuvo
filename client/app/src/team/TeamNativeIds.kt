package kinuvo.app.team

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.team.Team
import kinuvo.protocol.team.TeamEntry
import kinuvo.protocol.team.TeamId
import kinuvo.protocol.team.TeamInteraction
import kinuvo.protocol.team.TeamMember
import kinuvo.protocol.team.TeamSpec

/** String access for Swift, whose Objective-C bridge hides value classes. */
object TeamNativeIds {
    fun id(team: Team) = team.id.value
    fun defaultMember(team: Team) = team.defaultMember.value
    fun target(entry: TeamEntry) = entry.target?.value
    fun interaction(pending: TeamInteraction) = pending.interaction.id.value
    fun respond(model: TeamViewModel, pending: TeamInteraction, answer: InteractionAnswer) = model.respond(pending.interaction.id, answer)
    fun agent(member: TeamMember) = member.agentId.value
    fun session(member: TeamMember) = member.sessionId.value
    fun agent(entry: TeamEntry) = entry.agentId?.value
    fun agent(pending: TeamInteraction) = pending.agentId.value
    fun viewModel(repository: TeamRepository, id: String) = TeamViewModel(repository, TeamId(id))
    fun mention(model: TeamViewModel, agent: String) = model.mention(AgentId(agent))
    fun spec(name: String, members: List<String>, defaultMember: String? = null) = TeamSpec(name, members.map(::AgentId), defaultMember?.let(::AgentId))
}
