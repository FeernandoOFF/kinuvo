package kinuvo.harness.apple

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.uuid.Uuid

/**
 * Swift owns Foundation Models; Kotlin owns the agent loop, tools and persistence. [model] is [SYSTEM] (on-device) or
 * [PRIVATE_CLOUD] (Private Cloud Compute). [toolsJson] lists the [AppleTool]s the model may request, `[]` for plain chat;
 * [completion] gets the turn as JSON, `{"tool":"reply","reply":…}` or `{"tool":<name>, …its arguments}`, or an error.
 */
interface AppleLanguageModel {
    fun generate(requestId: String, model: String, instructions: String, prompt: String, toolsJson: String,
        onText: (String) -> Unit, completion: (String?, String?) -> Unit)
    fun cancel(requestId: String)
    /** Why [model] can't run on this device now, or null when it can. */
    fun unavailableReason(model: String): String?
    fun unavailableCode(model: String): String?
    /** Gives [completion] [model]'s context window in tokens; 0 when this build doesn't offer it. */
    fun contextSize(model: String, completion: (Int) -> Unit)
}

const val SYSTEM = "system"
const val PRIVATE_CLOUD = "private-cloud"

class AppleProvider(private val native: AppleLanguageModel) : ModelProvider, HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.apple", "Apple")
    override val id = "apple"
    override val name = "Apple"
    override val modelProviders get() = listOf(this)
    override suspend fun models() = withContext(Dispatchers.Main) {
        listOf(SYSTEM to "Apple on-device model", PRIVATE_CLOUD to "Apple Private Cloud Compute").mapNotNull { (id, name) ->
            suspendCoroutine { c -> native.contextSize(id) { c.resume(it) } }.takeIf { it > 0 }
                ?.let { ModelInfo(id, name, reasoning = emptyList(), input = listOf("text"), tools = true, context = it) }
        }
    }

    override fun usableTools(model: String, tools: List<ToolDescriptor>) = appleTools(tools, if (model == SYSTEM) 6 else 16)

    override suspend fun availability(model: String) = withContext(Dispatchers.Main) {
        native.unavailableReason(model)?.let { CapabilityState.unavailable(it, native.unavailableCode(model) ?: "unavailable") }
            ?: CapabilityState()
    }

    override fun model(config: AgentConfig): Model {
        require(config.provider == id && config.model in setOf(SYSTEM, PRIVATE_CLOUD)) { "Unsupported Apple model" }
        return Model { instructions, history, tools -> callbackFlow {
            val requestId = Uuid.random().toString()
            val offered = tools.mapNotNull { it.toApple() }
            var emitted = ""
            withContext(Dispatchers.Main) {
                native.generate(requestId, config.model, instructions, promptFor(history), ProtocolJson.encodeToString(offered), onText = { snapshot ->
                    if (snapshot.startsWith(emitted)) {
                        val delta = snapshot.drop(emitted.length)
                        if (delta.isNotEmpty()) trySend(StreamFrame.TextDelta(delta))
                        emitted = snapshot
                    }
                }, completion = { result, error ->
                    if (error != null) close(IllegalStateException(error))
                    else try {
                        val turn = ProtocolJson.parseToJsonElement(result ?: error("No model response")).jsonObject
                        val tool = turn["tool"]?.jsonPrimitive?.content ?: REPLY
                        if (tool == REPLY) {
                            val reply = turn["reply"]?.jsonPrimitive?.content.orEmpty()
                            check(reply.startsWith(emitted)) { "The model revised an already streamed reply" }
                            val delta = reply.drop(emitted.length)
                            if (delta.isNotEmpty()) trySend(StreamFrame.TextDelta(delta))
                        } else {
                            check(offered.any { it.name == tool }) { "The model asked for $tool, which it wasn't offered" }
                            trySend(StreamFrame.ToolCallComplete(Uuid.random().toString(), tool, JsonObject(turn - "tool").toString()))
                        }
                        trySend(StreamFrame.End())
                        close()
                    } catch (e: Exception) { close(e) }
                })
            }
            awaitClose { native.cancel(requestId) }
        }.buffer(Channel.UNLIMITED) }
    }
}

/** A tool as Apple's model sees it: flat parameters of simple types, so they fit its schema and small context. */
@Serializable
internal data class AppleTool(val name: String, val description: String, val parameters: List<AppleParameter>)

/** [type] is string, integer, number, boolean or array (of [items]); [values] limits a string to some values. */
@Serializable
internal data class AppleParameter(val name: String, val description: String, val type: String, val required: Boolean,
    val values: List<String>? = null, val items: String? = null)

/** The tools an Apple model takes: Memory and web search first, at most [max], skipping ones it can't fill in. */
internal fun appleTools(tools: List<ToolDescriptor>, max: Int = 6): List<ToolDescriptor> = tools
    .filter { it.name !in SKIPPED && it.toApple() != null }
    .sortedWith(compareBy<ToolDescriptor> { PREFERRED.indexOf(it.name).let { i -> if (i < 0) PREFERRED.size else i } }.thenBy { it.name })
    .take(max)

private fun ToolDescriptor.toApple(): AppleTool? {
    val parameters = requiredParameters.map { it to true } + optionalParameters.map { it to false }
    return AppleTool(name, description, parameters.map { (p, required) -> p.apple(required) ?: return null })
}

private fun ToolParameterDescriptor.apple(required: Boolean): AppleParameter? {
    fun simple(type: ToolParameterType) = when (type) {
        ToolParameterType.String, is ToolParameterType.Enum -> "string"
        ToolParameterType.Integer -> "integer"
        ToolParameterType.Float -> "number"
        ToolParameterType.Boolean -> "boolean"
        else -> null
    }
    return when (val t = type) {
        is ToolParameterType.Enum -> AppleParameter(name, description, "string", required, t.entries.toList())
        is ToolParameterType.List -> simple(t.itemsType)?.let { AppleParameter(name, description, "array", required, items = it) }
        else -> simple(t)?.let { AppleParameter(name, description, it, required) }
    }
}

private const val REPLY = "reply"
private val PREFERRED = listOf(ToolNames.MEMORY, ToolNames.WEB_SEARCH, ToolNames.WEB_FETCH)
/** Tools that need more context or steps than the on-device model has. */
private val SKIPPED = setOf(ToolNames.SKILLS, ToolNames.ACTIVATE_SKILL, ToolNames.SHELL, ToolNames.KNOWLEDGE, ToolNames.KNOWLEDGE_REWRITE,
    ToolNames.TASKS, ToolNames.ARTIFACT, ToolNames.SCHEDULE, ToolNames.DEVICE_TOOLS, ToolNames.ASK_USER)

internal fun promptFor(history: List<SessionEntry>): String = buildString {
    val context = history.filterIsInstance<SessionEntry.Context>().lastOrNull()
    context?.let { append("${it.content}\n\n") }
    for (entry in history) when (entry) {
        is SessionEntry.UserMessage -> append("User: ${entry.modelText}\n\n")
        is SessionEntry.AssistantMessage -> append("Agent: ${entry.text}\n\n")
        is SessionEntry.ToolCall -> append("Agent requested ${entry.name}: ${entry.args}\n\n")
        is SessionEntry.ToolResult -> append("Tool ${entry.name} ${if (entry.isError) "failed" else "result"}: ${entry.output}\n\n")
        is SessionEntry.Compaction -> append("Earlier conversation summary: ${entry.summary}\n\n")
        else -> Unit
    }
    append("Follow your instructions using the conversation and tool results above. Do not repeat a tool call that already succeeded.\n")
}
