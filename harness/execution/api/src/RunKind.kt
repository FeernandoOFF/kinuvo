package kinuvo.harness.execution.api

import kinuvo.protocol.Attachment
import kinuvo.protocol.Routine
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.RequestMetrics
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.UiCatalog

/** What a prompt asks the harness to run. The built-in harness picks its strategy by kind; an external harness only sees chat turns. */
sealed interface RunKind {
    /** A turn the user, a Task or a finished background tool started. The Server hands the run what it composes its tools from. */
    class Chat(
        val activation: SessionEntry.Activation? = null,
        val attachments: List<Attachment> = emptyList(),
        val voice: Boolean = false,
        val report: SessionEntry.ToolReport? = null,
        val catalogs: List<UiCatalog>? = null,
        val reports: ToolReports? = null,
        val taskId: String? = null,
    ) : RunKind

    /** An unattended Routine run; the prompt's text is the Routine's own. */
    class Routine(val turn: RoutineTurn) : RunKind

    /** Reflection on a finished conversation; the Server stages each stage's tools and keeps its result. */
    class Reflection(val turn: ReflectionTurn) : RunKind
}

/** One unattended run of a Routine as the Server sees it: what it knows going in, and how the Agent's report reaches the user. */
interface RoutineTurn {
    val routine: Routine
    val ref: RoutineRef
    var outcome: SessionEntry.Outcome?
    /** Tool calls nobody approved in time. */
    val skipped: MutableList<String>
    /** The run's first message, from the Routine's [prompt]. */
    fun message(prompt: String): String
    /** Settles the outcome from the Agent's closing [reply]. */
    fun decide(reply: String)
    suspend fun notify(outcome: SessionEntry.Outcome)
}

/** One stage of a Reflection: [request] frames it for the model, [tools] and [gate] are all it may call. */
class ReflectionStep(val request: String, val tools: ToolProvider, val gate: ToolGate)

/** A Reflection the Server stages: the harness loops over [stages] and reports failures and request metrics back. */
interface ReflectionTurn {
    val stages: List<String>
    /** Null when the stage has nothing to do. */
    suspend fun prepare(stage: String): ReflectionStep?
    fun failed(message: String)
    fun measured(metrics: RequestMetrics)
}
