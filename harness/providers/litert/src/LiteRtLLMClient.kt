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
import kinuvo.harness.core.takeWhole
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.uuid.Uuid
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import kotlinx.coroutines.CancellationException

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
        val history = liteRtHistory(prompt.messages)
        val last = requireNotNull(history.lastOrNull()) { "Nothing to answer" }
        log.debug { "${local.id}: ${history.size} messages, tools ${tools.joinToString { it.name }}" }
        engines.use(local) { engine ->
            var retry: LiteRtMessage? = null
            for (attempt in 0..RETRIES) {
                val config = ConversationConfig(
                    systemInstruction = prompt.messages.filterIsInstance<Message.System>().joinToString("\n\n") { it.textContent() }.takeIf { it.isNotBlank() }?.let { Contents.of(it.liteRtText()) },
                    initialMessages = if (retry == null) history.dropLast(1) else history,
                    tools = tools.map { tool(DescribedTool(it)) },
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
                            message.toolCalls.forEach { emit(StreamFrame.ToolCallComplete(Uuid.random().toString(), it.name, it.arguments.toJson().toString())) }
                        }
                        null
                    } catch (e: CancellationException) {
                        conversation.cancelProcess()
                        throw e
                    } catch (e: Exception) {
                        // Small models sometimes write a call LiteRT-LM can't parse, e.g. an invented tool name; they usually fix it when told.
                        if (attempt == RETRIES || e.message?.contains("Failed to parse tool calls") != true) throw e
                        LiteRtMessage.user(("Your tool call couldn't be read: ${e.message?.substringAfter("code block: ")?.substringBefore('\n')?.takeWhole(300)}. " +
                            "Call one of these tools by its exact name with arguments that match its parameters: ${tools.joinToString { it.name }}. Or answer without a tool.").liteRtText())
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

/** [history] as LiteRT-LM messages: tool results become tool messages and tool calls stay on the model's turn. */
internal fun liteRtHistory(history: List<Message>): List<LiteRtMessage> = history.flatMap { message ->
    when (message) {
        is Message.System -> emptyList()
        is Message.User -> listOfNotNull(
            message.parts.filterIsInstance<MessagePart.Tool.Result>().takeIf { it.isNotEmpty() }?.let { results ->
                LiteRtMessage.tool(Contents.of(results.map { Content.ToolResponse(it.tool, response(it.output, it.isError)) }))
            },
            message.textContent().takeIf { it.isNotBlank() }?.let { LiteRtMessage.user(it.liteRtText()) },
        )
        is Message.Assistant -> {
            val calls = message.parts.filterIsInstance<MessagePart.Tool.Call>().map { ToolCall(it.tool, it.argsJson.toMap()) }
            val text = message.textContent().liteRtText()
            if (calls.isEmpty() && text.isBlank()) emptyList()
            else listOf(LiteRtMessage.model(if (text.isBlank()) Contents.of(emptyList()) else Contents.of(text), calls, emptyMap()))
        }
    }
}

private fun response(output: String, error: Boolean): Any = runCatching { Json.parseToJsonElement(output).jsonObject.toMap() }.getOrNull()
    ?.let { if (error) it + ("error" to true) else it } ?: output.liteRtText().let { if (error) mapOf("error" to it) else it }

/** A tool LiteRT-LM describes to the model but never runs: automatic tool calling is off. */
private class DescribedTool(private val descriptor: ToolDescriptor) : OpenApiTool {
    override fun getToolDescriptionJsonString() = buildJsonObject {
        put("name", descriptor.name)
        put("description", descriptor.description)
        put("parameters", if (descriptor is RawSchemaToolDescriptor) descriptor.schema else schemas.generate(descriptor))
    }.toString().liteRtText()

    override fun execute(paramsJsonString: String) = error("${descriptor.name} runs in the agent loop")

    private companion object { val schemas = OpenAICompatibleToolDescriptorSchemaGenerator() }
}

private fun JsonObject.toMap(): Map<String, Any?> = entries.associate { it.key.liteRtText() to it.value.toValue() }

private fun JsonElement.toValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> toMap()
    is JsonArray -> map { it.toValue() }
    is JsonPrimitive -> if (isString) content.liteRtText() else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
}

/**
 * [this] with every surrogate, paired or lone, replaced by U+FFFD. LiteRT-LM's JNI passes text as modified UTF-8, which its JSON
 * parser rejects outside the BMP (emoji, Gothic) by aborting the process, so nothing else may reach it.
 */
internal fun String.liteRtText(): String = if (none { it.isSurrogate() }) this else replace(SURROGATES, "\uFFFD")

private val SURROGATES = Regex("[\\x{10000}-\\x{10FFFF}]|\\p{Cs}")

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
