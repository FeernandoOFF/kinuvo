package kinuvo.harness.apple

import kinuvo.harness.core.plugin.*
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.simplified
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.ExecutionLocation
import kinuvo.protocol.ModelTier
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.onEach
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
import kinuvo.protocol.PluginDefinition

/**
 * Swift owns Foundation Models; Kotlin owns the agent loop, tools and persistence. [model] is [SYSTEM] (on-device) or
 * [PRIVATE_CLOUD] (Private Cloud Compute). [toolsJson] lists the [AppleTool]s the model may request, `[]` for plain chat;
 * [completion] gets the turn as JSON, `{"tool":"reply","reply":…}` or `{"tool":<name>, …its arguments}`, or an error.
 */
interface AppleLanguageModel {
    fun generate(requestId: String, model: String, instructions: String, prompt: String, toolsJson: String,
        onText: (String) -> Unit, completion: (String?, String?) -> Unit)
    fun cancel(requestId: String)
    fun close() {}
    /** Why [model] can't run on this device now, or null when it can. */
    fun unavailableReason(model: String): String?
    fun unavailableCode(model: String): String?
    /** Gives [completion] [model]'s context window in tokens; 0 when this build doesn't offer it. */
    fun contextSize(model: String, completion: (Int) -> Unit)
}

const val SYSTEM = "system"
const val PRIVATE_CLOUD = "private-cloud"

class AppleProvider(private val native: AppleLanguageModel, private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val estimateTokens: (suspend (Prompt, List<ToolDescriptor>) -> Int)? = null) : ModelProvider, HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.apple", "Apple")
    override val id = "apple"
    override val name = "Apple"
    override val modelProviders get() = listOf(this)
    override suspend fun models() = withContext(dispatcher) {
        listOf(SYSTEM to "Apple on-device model", PRIVATE_CLOUD to "Apple Private Cloud Compute").mapNotNull { (id, name) ->
            suspendCoroutine { c -> native.contextSize(id) { c.resume(it) } }.takeIf { it > 0 }
                ?.let { ModelInfo(id, name, reasoning = emptyList(), input = listOf("text"), tools = true, context = it,
                    location = ExecutionLocation.ServerHost, tier = if (id == SYSTEM) ModelTier.Micro else ModelTier.Small, state = availability(id)) }
        }
    }

    override fun profile(model: String, base: ModelProfile) =
        if (model == PRIVATE_CLOUD && base.tier == ModelTier.Small) base.copy(tools = base.tools.copy(cap = 16)) else base
    override fun accepts(model: String, tool: ToolDescriptor) = tool.toApple() != null

    override suspend fun availability(model: String) = withContext(dispatcher) {
        native.unavailableReason(model)?.let { CapabilityState.unavailable(it, native.unavailableCode(model) ?: "unavailable") }
            ?: CapabilityState()
    }

    private val client = AppleLLMClient(native, dispatcher)
    val promptExecutor = koogExecutor(client)

    override fun model(config: AgentConfig): AgentModel = model(config, null)

    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel {
        require(config.provider == id && config.model in setOf(SYSTEM, PRIVATE_CLOUD)) { "Unsupported Apple model" }
        return AgentModel(promptExecutor, (info ?: ModelInfo(config.model, reasoning = emptyList(), input = listOf("text"), tools = true)).llModel(Apple),
            instructionsInPrompt = false, estimateTokens = estimateTokens)
    }

    override suspend fun close() { native.close(); promptExecutor.close() }
}

private object Apple : LLMProvider("apple", "Apple")

class AppleLLMClient(private val native: AppleLanguageModel, private val dispatcher: CoroutineDispatcher = Dispatchers.Main) : LLMClient() {
    override fun llmProvider(): LLMProvider = Apple
    override fun close() {}
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Apple moderation is unavailable")
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val capture = currentCoroutineContext()[TokenUsageCapture]
        return executeStreaming(prompt, model, tools).onEach { capture?.recordFrame?.invoke(it) }.toList().toMessageResponse()
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = callbackFlow {
        val requestId = Uuid.random().toString()
        val originals = tools.mapNotNull { it.toApple() }
        val offered = appleOffer(tools)
        val routes = offered.zip(originals).associate { (short, original) -> short.name to original.name }
        var emitted = ""
        withContext(dispatcher) {
            native.generate(requestId, model.id, appleInstructions(prompt.messages), applePrompt(prompt.messages, tools), ProtocolJson.encodeToString(offered), onText = { snapshot ->
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
                        trySend(StreamFrame.ToolCallComplete(Uuid.random().toString(), routes.getValue(tool), JsonObject(turn - "tool").toString()))
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

/** Compact model-facing names route back to the full tool identity after generation. */
internal fun appleOffer(tools: List<ToolDescriptor>): List<AppleTool> = tools.mapNotNull { it.toApple() }.mapIndexed { index, tool ->
    if ("__" !in tool.name) tool else tool.copy(name = "t${index}_${tool.name.substringBeforeLast("__").substringAfterLast("__")}")
}

internal fun ToolDescriptor.toApple(): AppleTool? {
    val flat = simplified(SchemaLevel.Flat) ?: return null
    val parameters = flat.requiredParameters.map { it to true } + flat.optionalParameters.map { it to false }
    return AppleTool(name, description, parameters.map { (p, required) -> p.apple(required) })
}

private fun ToolParameterDescriptor.apple(required: Boolean): AppleParameter {
    fun simple(type: ToolParameterType) = when (type) {
        ToolParameterType.Integer -> "integer"
        ToolParameterType.Float -> "number"
        ToolParameterType.Boolean -> "boolean"
        else -> "string"
    }
    return when (val t = type) {
        is ToolParameterType.Enum -> AppleParameter(name, description, "string", required, t.entries.toList())
        is ToolParameterType.List -> AppleParameter(name, description, "array", required, items = simple(t.itemsType))
        else -> AppleParameter(name, description, simple(t), required)
    }
}

private const val REPLY = "reply"

/** The basic context and active Skills; the Session's full instructions aren't sent to Apple models. */
internal fun appleInstructions(history: List<Message>) = history.filterIsInstance<Message.System>().joinToString("\n\n") { it.textContent() }

internal fun applePrompt(history: List<Message>, tools: List<ToolDescriptor>): String {
    val original = tools.mapNotNull { it.toApple() }
    val names = original.zip(appleOffer(tools)).associate { (full, short) -> full.name to short.name }
    return promptFor(history) { names[it] ?: it }
}

/**
 * Only the latest user message and this turn's tool steps: given earlier turns, the 3B model copies a prior reply
 * verbatim or answers an earlier message. Durable facts reach it through Memory and User Notes instead.
 */
internal fun promptFor(history: List<Message>, toolName: (String) -> String = { it }): String = buildString {
    val latest = history.indexOfLast { it is Message.User && it.textContent().isNotEmpty() }.coerceAtLeast(0)
    for (message in history.drop(latest)) when (message) {
        is Message.System -> Unit
        is Message.User -> {
            if (message.textContent().isNotEmpty()) append("User: ${message.textContent()}\n\n")
            message.parts.filterIsInstance<MessagePart.Tool.Result>().forEach {
                append("Tool ${toolName(it.tool)} ${if (it.isError) "failed" else "result"}: ${it.output}\n\n")
            }
        }
        is Message.Assistant -> {
            if (message.textContent().isNotEmpty()) append("Agent: ${message.textContent()}\n\n")
            message.parts.filterIsInstance<MessagePart.Tool.Call>().forEach {
                append("Agent requested ${toolName(it.tool)}: ${it.args}\n\n")
            }
        }
    }
    if (history.none { it is Message.System && it.textContent().contains("Answer the user from these results") })
        append("Answer the user's message using the tool results above. Do not repeat an action already completed for this request. If the tool results satisfy the request, reply to the user now.\n")
}
