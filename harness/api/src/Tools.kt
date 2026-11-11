package kinuvo.harness

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ToolIdentity
import kotlinx.serialization.json.JsonObject

/**
 * A tool as a run sees it: [schema] is the JSON Schema of its arguments. [verbatim] means the schema was authored as JSON
 * Schema (e.g. an MCP tool's) and is sent as given. [aliases] are names older Sessions still call it by.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val verbatim: Boolean = false,
    val identity: ToolIdentity? = null,
    val aliases: List<String> = emptyList(),
    val repeatable: Boolean = true,
    /** Run bookkeeping the loop relies on; outside the Tool-call budget. */
    val internal: Boolean = false,
    val search: Boolean = false,
    val paging: Boolean = false,
)

/** The tools a run offers. */
interface ToolProvider {
    /** Everything the run can call. */
    val tools: List<ToolSpec>
    /** What the model is sent: a subset of [tools], sorted by name. */
    val advertised: List<ToolSpec>
}

/** One turn's tool activity: where the gate reports it, and how many Tool-call budget calls it has executed. */
class ToolTurn(
    val emit: suspend (AgentEvent) -> Unit = {},
    /** Replaces a running call's live output. */
    val progress: suspend (AgentEvent.ToolCallResult) -> Unit = { emit(it) },
    /** Told what to record for a call still waiting, if the run is cancelled meanwhile. */
    val stopped: (String) -> Unit = {},
) {
    var executedCalls: Int = 0
}

/**
 * The only way to run a tool call: applies Tool scope, Deny and Trust rules, Risk and Approvals, limits and redaction,
 * and answers calls a tool makes to the run's other tools. Refusals and failures come back as error results.
 */
interface ToolGate {
    suspend fun invoke(call: SessionEntry.ToolCall, turn: ToolTurn = ToolTurn()): SessionEntry.ToolResult
}
