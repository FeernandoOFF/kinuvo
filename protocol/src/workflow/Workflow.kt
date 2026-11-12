package kinuvo.protocol.workflow

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.session.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable @JvmInline value class WorkflowId(val value: String) { override fun toString() = value }
@Serializable @JvmInline value class WorkflowRunId(val value: String) { override fun toString() = value }
@Serializable @JvmInline value class StepId(val value: String) { override fun toString() = value }
@Serializable data class Position(val x: Float, val y: Float)
@Serializable enum class StepSession { New, Continue }

@Serializable
sealed interface WorkflowStep {
    val id: StepId
    val position: Position?

    @Serializable @SerialName("agent_turn")
    data class AgentTurn(override val id: StepId, val agentId: AgentId, val prompt: String,
        val session: StepSession = StepSession.New, override val position: Position? = null) : WorkflowStep

    @Serializable @SerialName("approval")
    data class Approval(override val id: StepId, val text: String, override val position: Position? = null) : WorkflowStep
}

@Serializable data class WorkflowEdge(val from: StepId, val to: StepId)
@Serializable data class Workflow(
    val id: WorkflowId, val name: String, val steps: List<WorkflowStep>, val edges: List<WorkflowEdge> = emptyList(),
    val trigger: Trigger? = null, val timeZone: String = "UTC", val enabled: Boolean = true, val createdAt: Long = 0,
)
@Serializable enum class WorkflowRunStatus { Running, Done, Failed, Cancelled }
@Serializable enum class StepStatus { Pending, Running, WaitingApproval, Done, Failed, Skipped, Cancelled }
@Serializable data class StepRun(val status: StepStatus = StepStatus.Pending, val sessionId: SessionId? = null,
    val output: String? = null, val error: String? = null, val interactionId: InteractionId? = null)
@Serializable data class WorkflowRun(val id: WorkflowRunId, val workflowId: WorkflowId,
    val status: WorkflowRunStatus, val startedAt: Long, val endedAt: Long? = null,
    val steps: Map<StepId, StepRun>, val workflow: Workflow)
