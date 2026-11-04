package kinuvo.harness.litert

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import kinuvo.harness.core.TokenUsageCapture
import kinuvo.harness.core.toolLocalName
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.uuid.Uuid
import com.google.ai.edge.litertlm.Message as LiteRtMessage

private val log = KotlinLogging.logger {}

/** Runs each request as a fresh LiteRT-LM conversation over the prompt's history; Kotlin's agent loop runs the tools. */
internal class LiteRtLLMClient(private val engines: Engines) : LLMClient() {
    override fun llmProvider(): LLMProvider = LiteRt
    override fun close() {}
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("On-device moderation is unavailable")

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val capture = currentCoroutineContext()[TokenUsageCapture]
        return executeStreaming(prompt, model, tools).onEach { capture?.recordFrame?.invoke(it) }.toList().toMessageResponse()
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
        val local = requireNotNull(localModels.firstOrNull { it.id == model.id }) { "No on-device model '${model.id}'" }
        val names = shortNames(tools)
        val aliases = names.entries.associate { (alias, short) -> short to alias }
        val history = liteRtHistory(prompt.messages) { names[it] ?: toolLocalName(it) }
        val last = requireNotNull(history.lastOrNull()) { "Nothing to answer" }
        log.debug { "${local.id}: ${history.size} messages, tools ${tools.joinToString { it.name }}" }
        engines.use(local) { engine ->
            var retry: LiteRtMessage? = null
            for (attempt in 0..RETRIES) {
                val config = ConversationConfig(
                    systemInstruction = prompt.messages.filterIsInstance<Message.System>().joinToString("\n\n") { it.textContent() }.takeIf { it.isNotBlank() }?.let(Contents::of),
                    initialMessages = if (retry == null) history.dropLast(1) else history,
                    tools = tools.map { tool(DescribedTool(it, names.getValue(it.name))) },
                    automaticToolCalling = false,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    maxOutputToken = MAX_OUTPUT,
                )
                val outcome = engine.createConversation(config).use { conversation ->
                    var text = ""
                    try {
                        conversation.sendMessageAsync(retry ?: last).collect { message ->
                            val delta = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                            if (delta.isNotEmpty()) { text += delta; emit(StreamFrame.TextDelta(delta)) }
                            message.toolCalls.forEach { emit(StreamFrame.ToolCallComplete(Uuid.random().toString(), aliases[it.name] ?: it.name, it.arguments.toJson().toString())) }
                        }
                        null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        conversation.cancelProcess()
                        throw e
                    } catch (e: Exception) {
                        // Small models sometimes write a call LiteRT-LM can't parse, e.g. an invented tool name; they usually fix it when told.
                        if (attempt == RETRIES || e.message?.contains("Failed to parse tool calls") != true) throw e
                        LiteRtMessage.user("Your tool call couldn't be read: ${e.message?.substringAfter("code block: ")?.substringBefore('\n')?.take(300)}. " +
                            "Call one of these tools by its exact name with arguments that match its parameters: ${names.values.joinToString()}. Or answer without a tool.")
                    }.also { if (it == null && text.isNotEmpty()) emit(StreamFrame.TextComplete(text)) }
                }
                if (outcome == null) break
                retry = outcome
            }
            emit(StreamFrame.End())
        }
    }

    private companion object {
        const val RETRIES = 2
        const val MAX_OUTPUT = 1024
    }
}

/** Short names the model can repeat, e.g. `memory` for a Plugin tool's long alias; ones that would clash keep their alias. */
internal fun shortNames(tools: List<ToolDescriptor>): Map<String, String> {
    val short = tools.associate { it.name to toolLocalName(it.name) }
    val clashing = short.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return short.mapValues { (alias, name) -> if (name in clashing) alias else name }
}

/** [history] as LiteRT-LM messages, with tools called by [name]: results become tool messages and calls stay on the model's turn. */
internal fun liteRtHistory(history: List<Message>, name: (String) -> String = { it }): List<LiteRtMessage> = history.flatMap { message ->
    when (message) {
        is Message.System -> emptyList()
        is Message.User -> listOfNotNull(
            message.parts.filterIsInstance<MessagePart.Tool.Result>().takeIf { it.isNotEmpty() }?.let { results ->
                LiteRtMessage.tool(Contents.of(results.map { Content.ToolResponse(name(it.tool), response(it.output, it.isError)) }))
            },
            message.textContent().takeIf { it.isNotBlank() }?.let { LiteRtMessage.user(it) },
        )
        is Message.Assistant -> {
            val calls = message.parts.filterIsInstance<MessagePart.Tool.Call>().map { ToolCall(name(it.tool), it.argsJson.toMap()) }
            val text = message.textContent()
            if (calls.isEmpty() && text.isBlank()) emptyList()
            else listOf(LiteRtMessage.model(if (text.isBlank()) Contents.of(emptyList()) else Contents.of(text), calls, emptyMap()))
        }
    }
}

private fun response(output: String, error: Boolean): Any = runCatching { Json.parseToJsonElement(output).jsonObject.toMap() }.getOrNull()
    ?.let { if (error) it + ("error" to true) else it } ?: if (error) mapOf("error" to output) else output

/** A tool LiteRT-LM describes to the model but never runs: automatic tool calling is off. */
private class DescribedTool(private val descriptor: ToolDescriptor, private val name: String) : OpenApiTool {
    override fun getToolDescriptionJsonString() = buildJsonObject {
        put("name", name)
        put("description", descriptor.description)
        put("parameters", if (descriptor is RawSchemaToolDescriptor) descriptor.schema else schemas.generate(descriptor))
    }.toString()

    override fun execute(paramsJsonString: String) = error("$name runs in the agent loop")

    private companion object { val schemas = OpenAICompatibleToolDescriptorSchemaGenerator() }
}

private fun JsonObject.toMap(): Map<String, Any?> = mapValues { it.value.toValue() }

private fun JsonElement.toValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> toMap()
    is JsonArray -> map { it.toValue() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
}

internal fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.toJson() })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Boolean -> JsonPrimitive(this)
    is Double -> if (this % 1.0 == 0.0 && kotlin.math.abs(this) < 1e15) JsonPrimitive(toLong()) else JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}
