package kinuvo.harness

import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.execution.AgentRegistration
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.execution.ExecutionConnection
import kinuvo.protocol.execution.HarnessConnection
import kinuvo.protocol.model.ModelInfo
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.surface.SurfaceAction
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.RunId
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

/** The connection [ExecutionFactory.open] takes for the built-in harness. */
val BUILT_IN_HARNESS = HarnessConnection("kinuvo", "Kinuvo", ExecutionConnection.BuiltIn)

fun builtInRegistration(agent: Agent) = AgentRegistration(agent.id, agent.name, BUILT_IN_HARNESS.id)

/** One turn of an Agent. */
interface AgentRunner {
    fun run(
        sessionId: SessionId,
        userText: String,
        activation: SessionEntry.Activation? = null,
        runId: RunId = RunId(Uuid.random().toString()),
        attachments: List<Attachment> = emptyList(),
        voice: Boolean = false,
        action: SurfaceAction? = null,
        report: SessionEntry.ToolReport? = null,
    ): Flow<AgentEvent>
}

/** The model a run asks for: [config] names the provider and model, [info] is its catalog entry. The built-in harness builds it from the sources a host registered. */
class ModelSelection(val config: AgentConfig, val info: ModelInfo?)

/** What a Server composes for one turn of the built-in harness; [traces] is where it records, when it does. */
class NativeRun(
    val agent: Agent,
    val store: SessionStorage,
    val model: ModelSelection,
    val tools: ToolProvider,
    val gate: ToolGate,
    val warnings: List<String> = emptyList(),
    val context: String = "",
    val contextTokens: Int? = null,
    val routine: RoutineTurn? = null,
    val profile: ModelProfile,
    val traces: Path? = null,
)

/** The Agent and model a Reflection runs with; [fallback] runs instead when [model] can't be built. */
class NativeReflection(val agent: Agent, val model: ModelSelection, val traces: Path? = null, val fallback: NativeReflection? = null)

/** What the built-in harness asks the Server that hosts it for when it runs a prompt: the Server composes tools, instructions and models. */
interface NativeHarnessHost {
    /** The turn for a chat or Routine run of [sessionId]. */
    suspend fun run(agentId: AgentId, sessionId: SessionId, input: ExecutionInput): NativeRun

    /** The Agent and model the Reflection of [sessionId] runs with. */
    suspend fun reflection(agentId: AgentId, sessionId: SessionId): NativeReflection
}

/** The built-in harness as a Server uses it: sessions through [open], and the loop of a composed turn through [runner]. */
interface NativeHarness : ExecutionFactory, AutoCloseable {
    fun runner(run: NativeRun): AgentRunner

    override fun close() {}
}
