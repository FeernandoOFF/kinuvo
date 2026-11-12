package kinuvo.server.plugin

import kinuvo.protocol.approval.ChangePreview
import kinuvo.harness.ToolSpec
import kinuvo.protocol.tool.ToolGroup
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.session.SessionEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.plugin.ToolIdentity
import kinuvo.protocol.session.ToolCallId

/**
 * One thing a call does, e.g. one command of a shell chain. Trust rules match it by [words]: as a prefix, or all of
 * them when [exact]; "Always allow" saves [key]. [trustable] is false when no rule may cover it. A rule lowers its risk
 * only to [floor], what remains once the command itself is trusted, e.g. writing a file through a redirect.
 */
data class Action(
    val risk: Risk,
    val words: List<String>,
    val reason: String? = null,
    val exact: Boolean = false,
    val trustable: Boolean = true,
    val key: List<String> = words,
    val floor: Risk = Risk.Low,
    val alwaysAsk: Boolean = false,
) {
    fun matches(rule: List<String>) = !alwaysAsk && trustable && rule.isNotEmpty() && if (exact) rule == words else words.take(rule.size) == rule
}

/** What a call would do; its risk is that of its riskiest [actions]. */
data class Assessment(val actions: List<Action>) {
    val risk get() = actions.maxOf { it.risk }
    val reason get() = actions.filter { it.risk == risk }.firstNotNullOfOrNull { it.reason }
}

enum class ToolKind { Standard, Shell, ComputerAction, AskUser, SecretRequest }

interface Tool {
    val catalogTargets: List<Tool> get() = emptyList()
    val coreMetadata: CoreToolEntry? get() = null
    val kind: ToolKind get() = ToolKind.Standard
    val descriptor: ToolSpec
    val identity: ToolIdentity? get() = null
    val group: ToolGroup get() = ToolGroup.External
    val displayName: String get() = descriptor.name
    val risk: Risk get() = Risk.Medium
    /** Whether identical consecutive successful calls can be useful within one turn. */
    val repeatable: Boolean get() = true
    /** What a call with [args] would do; by default the whole tool at its [risk]. */
    fun assess(args: JsonObject): Assessment = Assessment(listOf(Action(risk, listOf(descriptor.name))))
    /** What the Approval of a call with [args] shows it would change; failing here refuses the call before anyone is asked. */
    suspend fun preview(args: JsonObject): ChangePreview? = null
    suspend fun bind(args: JsonObject): suspend () -> Unit = {}
    suspend fun executeResult(args: JsonObject, callId: String, run: ToolRun): SessionEntry.ToolResult =
        SessionEntry.ToolResult(ToolCallId(callId), descriptor.name, execute(args, callId, run))
    suspend fun execute(args: JsonObject): String
    suspend fun execute(args: JsonObject, callId: String): String = execute(args)
    /** Lets a tool call the [run]'s other tools, e.g. from a Script. */
    suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String = execute(args, callId)
}

val Tool.policyKey get() = requireNotNull(identity) { "A tool must be bound to a plugin instance before checking Approvals" }.key

fun Assessment.withToolKey(name: String, key: String): Assessment {
    fun words(value: List<String>) = if (value.firstOrNull() == name) listOf(key) + value.drop(1) else value
    return copy(actions = actions.map { it.copy(words = words(it.words), key = words(it.key)) })
}

/** Tool names route calls and Approvals; collisions must never replace another implementation. */
fun List<Tool>.indexedTools(): Map<String, Tool> = associateBy { it.descriptor.name }.also { indexed ->
    require(indexed.size == size) {
        val duplicates = groupingBy { it.descriptor.name }.eachCount().filterValues { it > 1 }.keys
        "Duplicate tool names: ${duplicates.joinToString()}"
    }
    val identities = mapNotNull { it.identity }
    require(identities.distinct().size == identities.size) { "Duplicate tool identities" }
}

/** The run a tool call is part of: its other tools, called as the model would, under the same Approvals. */
interface ToolRun {
    val tools: List<ToolSpec>
    /** Replaces this call's live output; the returned result is still the durable final output. */
    suspend fun progress(content: String) {}
    /** Runs work under Server ownership, reporting once even after this run ends. */
    fun background(started: String, work: suspend () -> String): String = error("Background reports are unavailable in this run")
    /** Runs [name] with [args], a JSON object; denials and failures come back as an error result. */
    suspend fun call(name: String, args: String): SessionEntry.ToolResult
    /** Shows the model the file [name] with [bytes] alongside this call's result. */
    suspend fun attach(name: String, bytes: ByteArray): Attachment = error("Files can't be shown to the model in this run")
}

fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content
fun JsonObject.requireString(key: String): String =
    requireNotNull(string(key)?.takeIf { it.isNotBlank() }) { "Missing '$key'" }
