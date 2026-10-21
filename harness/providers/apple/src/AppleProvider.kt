package kinuvo.harness.apple

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.ProtocolJson
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.flow.toList
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

    private val client = AppleLLMClient(native)
    val promptExecutor = koogExecutor(client)

    override fun model(config: AgentConfig): AgentModel = model(config, null)

    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel {
        require(config.provider == id && config.model in setOf(SYSTEM, PRIVATE_CLOUD)) { "Unsupported Apple model" }
        return AgentModel(promptExecutor, (info ?: ModelInfo(config.model, reasoning = emptyList(), input = listOf("text"), tools = true)).llModel(Apple))
    }

    override suspend fun close() { promptExecutor.close() }
}

private object Apple : LLMProvider("apple", "Apple")

class AppleLLMClient(private val native: AppleLanguageModel) : LLMClient() {
    override fun llmProvider(): LLMProvider = Apple
    override fun close() {}
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Apple moderation is unavailable")
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        executeStreaming(prompt, model, tools).toList().toMessageResponse()

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = callbackFlow {
            val requestId = Uuid.random().toString()
            val offered = tools.mapNotNull { it.toApple() }
            var emitted = ""
            withContext(Dispatchers.Main) {
                native.generate(requestId, model.id, prompt.messages.filterIsInstance<Message.System>().joinToString("\n\n") { it.textContent() }, promptFor(prompt.messages), ProtocolJson.encodeToString(offered), onText = { snapshot ->
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
                            trySend(StreamFrame.TextComplete(reply))
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
    }.buffer(Channel.UNLIMITED)
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

internal fun promptFor(history: List<Message>): String = buildString {
    for (message in history) when (message) {
        is Message.System -> Unit
        is Message.User -> {
            if (message.textContent().isNotEmpty()) append("User: ${message.textContent()}\n\n")
            message.parts.filterIsInstance<MessagePart.Tool.Result>().forEach {
                append("Tool ${it.tool} ${if (it.isError) "failed" else "result"}: ${it.content}\n\n")
            }
        }
        is Message.Assistant -> {
            if (message.textContent().isNotEmpty()) append("Agent: ${message.textContent()}\n\n")
            message.parts.filterIsInstance<MessagePart.Tool.Call>().forEach {
                append("Agent requested ${it.tool}: ${it.args}\n\n")
            }
        }
    }
    append("Follow your instructions using the conversation and tool results above. Do not repeat a tool call that already succeeded.\n")
}
