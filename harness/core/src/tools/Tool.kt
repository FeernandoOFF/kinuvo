package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.protocol.Risk
import kinuvo.protocol.SessionEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kinuvo.protocol.Attachment
import kinuvo.protocol.ToolIdentity

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

object ToolNames {
    const val GENERATE_IMAGE = "generate_image"
    const val MEMORY = "memory"
    const val WEB_FETCH = "web_fetch"
    /** Comes with [WEB_FETCH] when web search is set up. */
    const val WEB_SEARCH = "web_search"
    const val SKILLS = "skills"
    const val ACTIVATE_SKILL = "activate_skill"
    const val SHELL = "shell"
    const val SCHEDULE = "schedule"
    const val KNOWLEDGE = "knowledge"
    const val KNOWLEDGE_REWRITE = "knowledge_rewrite"
    const val TASKS = "tasks"
    const val ASK_USER = "ask_user"
    /** Offered when a Device has said which A2UI catalogs it renders. */
    const val SHOW_UI = "show_ui"
    const val SHOW_HTML = "show_html"
    /** Only in Routine runs. */
    const val ROUTINE_REPORT = "routine_report"
    const val ARTIFACT = "artifact"
    const val DEVICE_TOOLS = "device_tools"
    /** Tools to read the Agent's own Sessions, see the Agents it can work with and delegate work to them. */
    const val AGENT_TOOLS = "agent_tools"
    /** Tools to change the Agent's own settings, create Agents and manage its MCP servers, always with an Approval. */
    const val AGENT_MANAGE = "agent_manage"
    const val RUN_CODE = "run_code"
    const val COMPUTER = "computer"
    /** Its Trust rules are scoped to the app acted in. */
    const val COMPUTER_ACT = "computer_act"
    const val FIND_TOOLS = "find_tools"
    const val DESCRIBE_TOOL = "describe_tool"
    const val CALL_TOOL = "call_tool"

    /** The built-in tools an Agent's `tools:` can turn on. */
    val OPTIONAL = listOf(MEMORY, WEB_FETCH, SKILLS, SCHEDULE, KNOWLEDGE, TASKS, ASK_USER, ARTIFACT, DEVICE_TOOLS, AGENT_TOOLS, AGENT_MANAGE, RUN_CODE, COMPUTER, "browser")
    val DEFAULT = OPTIONAL
    /** Groups a plugin provides, offered only where one is installed. */
    val FROM_PLUGINS = setOf(RUN_CODE, "browser")
}

enum class ToolKind { Standard, Shell, ComputerAction, AskUser }

interface Tool {
    val kind: ToolKind get() = ToolKind.Standard
    val descriptor: ToolDescriptor
    val identity: ToolIdentity? get() = null
    val displayName: String get() = descriptor.name
    val risk: Risk get() = Risk.Medium
    /** Whether identical consecutive successful calls can be useful within one turn. */
    val repeatable: Boolean get() = true
    /** What a call with [args] would do; by default the whole tool at its [risk]. */
    fun assess(args: JsonObject): Assessment = Assessment(listOf(Action(risk, listOf(descriptor.name))))
    /** What a call with [args] would change, shown in its Approval; a call it rejects fails before the user is asked. */
    suspend fun describe(args: JsonObject): String? = null
    suspend fun bind(args: JsonObject): suspend () -> Unit = {}
    suspend fun executeResult(args: JsonObject, callId: String, run: ToolRun): SessionEntry.ToolResult =
        SessionEntry.ToolResult(callId, descriptor.name, execute(args, callId, run))
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
    val tools: List<ToolDescriptor>
    /** Runs [name] with [args], a JSON object; denials and failures come back as an error result. */
    suspend fun call(name: String, args: String): SessionEntry.ToolResult
    /** Shows the model the file [name] with [bytes] alongside this call's result. */
    suspend fun attach(name: String, bytes: ByteArray): Attachment = error("Files can't be shown to the model in this run")
}

fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content
fun JsonObject.requireString(key: String): String =
    requireNotNull(string(key)?.takeIf { it.isNotBlank() }) { "Missing '$key'" }
