package kinuvo.protocol.team

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.InteractionAnswer
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable @JvmInline
value class TeamId(val value: String) { override fun toString() = value }

@Serializable
data class TeamMember(val agentId: AgentId, val sessionId: SessionId, val cursor: Int = 0)

@Serializable
enum class TeamEntryKind { User, Reply, Handoff, Error }

@Serializable
data class TeamEntry(val sequence: Int, val kind: TeamEntryKind, val text: String, val agentId: AgentId? = null, val target: AgentId? = null)

@Serializable
data class Team(val id: TeamId, val name: String, val members: List<TeamMember>, val defaultMember: AgentId, val log: List<TeamEntry> = emptyList())

@Serializable
data class TeamInteraction(val agentId: AgentId, val sessionId: SessionId, val interaction: PendingInteraction)

@Serializable
data class TeamSnapshot(val team: Team, val runningMember: AgentId? = null, val pending: List<TeamInteraction> = emptyList())

@Serializable
data class TeamChanged(val id: TeamId, val snapshot: TeamSnapshot? = null)

@Serializable
data class TeamSpec(val name: String, val members: List<AgentId>, val defaultMember: AgentId? = null)

@Serializable
data class UpdateTeamParams(val id: TeamId, val spec: TeamSpec)

@Serializable
data class TeamSendParams(val id: TeamId, val text: String)

@Serializable
data class TeamRespondParams(val id: TeamId, val requestId: InteractionId, val answer: InteractionAnswer)
