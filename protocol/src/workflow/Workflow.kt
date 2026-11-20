package kinuvo.protocol.workflow

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.model.ProviderModelRef
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.session.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.jvm.JvmInline

@Serializable @JvmInline value class WorkflowId(val value: String) { override fun toString() = value }
@Serializable @JvmInline value class WorkflowRunId(val value: String) { override fun toString() = value }
@Serializable @JvmInline value class StepId(val value: String) { override fun toString() = value }
@Serializable data class Position(val x: Float, val y: Float)
@Serializable enum class StepSession { New, Continue }
@Serializable enum class ConditionOp { Equals, NotEquals, Contains, NotContains, Empty, NotEmpty, GreaterThan, LessThan }

@Serializable
sealed interface WorkflowStep {
    val id: StepId
    val position: Position?

    @Serializable @SerialName("agent_turn")
    data class AgentTurn(override val id: StepId, val agentId: AgentId, val prompt: String,
        val session: StepSession = StepSession.New, override val position: Position? = null, val model: ProviderModelRef? = null) : WorkflowStep

    @Serializable @SerialName("approval")
    data class Approval(override val id: StepId, val text: String, override val position: Position? = null) : WorkflowStep

    @Serializable @SerialName("review")
    data class Review(override val id: StepId, val text: String, val value: String = "", override val position: Position? = null, val surface: JsonObject? = null) : WorkflowStep

    @Serializable @SerialName("tool")
    data class Tool(override val id: StepId, val tool: String, val args: JsonObject = JsonObject(emptyMap()), override val position: Position? = null) : WorkflowStep

    @Serializable @SerialName("model")
    data class Model(override val id: StepId, val prompt: String, val system: String? = null, val model: ProviderModelRef? = null, override val position: Position? = null) : WorkflowStep

    /** Compares [left] with [right]; its output is `true` or `false`, and each outgoing edge may follow only one [WorkflowEdge.branch]. */
    /** Fixed text, references rendered, as its output: a starting value for the steps after it. */
    @Serializable @SerialName("text")
    data class Text(override val id: StepId, val text: String, override val position: Position? = null) : WorkflowStep

    @Serializable @SerialName("condition")
    data class Condition(override val id: StepId, val left: String, val op: ConditionOp = ConditionOp.Equals, val right: String = "", override val position: Position? = null) : WorkflowStep
}

/** [branch] is set only on an edge out of a Condition: the edge is followed when the Condition's result equals it. */
@Serializable data class WorkflowEdge(val from: StepId, val to: StepId, val branch: Boolean? = null)
/** A named value a run is started with, read by steps as `{{input.<name>}}`. */
@Serializable data class WorkflowInput(val name: String, val description: String = "", val default: String = "")
@Serializable data class Workflow(
    val id: WorkflowId, val name: String, val steps: List<WorkflowStep>, val edges: List<WorkflowEdge> = emptyList(),
    val trigger: Trigger? = null, val timeZone: String = "UTC", val enabled: Boolean = true, val createdAt: Long = 0,
    val agentId: AgentId? = null, val description: String = "", val inputs: List<WorkflowInput> = emptyList(),
)
/** An input typed as text: a JSON number, boolean, object or array is that value, anything else the text itself. */
fun inputValue(text: String): JsonElement {
    val parsed = try { Json.parseToJsonElement(text.trim()) } catch (_: IllegalArgumentException) { null }
    return when {
        parsed is JsonObject || parsed is JsonArray -> parsed
        parsed is JsonPrimitive && !parsed.isString && (parsed.booleanOrNull != null || parsed.doubleOrNull != null) -> parsed
        else -> JsonPrimitive(text)
    }
}
@Serializable data class RunWorkflowParams(val id: WorkflowId, val input: JsonObject = JsonObject(emptyMap()))
@Serializable enum class WorkflowRunStatus { Running, Done, Failed, Cancelled }
@Serializable enum class StepStatus { Pending, Running, WaitingApproval, Done, Failed, Skipped, Cancelled }
@Serializable data class StepRun(val status: StepStatus = StepStatus.Pending, val sessionId: SessionId? = null,
    val output: String? = null, val error: String? = null, val interactionId: InteractionId? = null,
    /** The tool is running and waits on a call it made, so a restart can't replay it. */
    val started: Boolean = false, val model: ProviderModelRef? = null)
@Serializable data class WorkflowRun(val id: WorkflowRunId, val workflowId: WorkflowId,
    val status: WorkflowRunStatus, val startedAt: Long, val endedAt: Long? = null,
    val steps: Map<StepId, StepRun>, val workflow: Workflow,
    val input: JsonObject = JsonObject(emptyMap()), val output: String? = null, val retryOf: WorkflowRunId? = null)

@Serializable
data class WorkflowRef(val id: WorkflowId, val name: String)
