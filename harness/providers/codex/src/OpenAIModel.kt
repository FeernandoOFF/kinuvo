package kinuvo.harness.codex

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openai.models.ReasoningSummary
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.Model
import kinuvo.harness.core.modelText
import kinuvo.harness.core.llModel
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.SessionEntry
import ai.koog.http.client.KoogHttpClientException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.JsonPrimitive

class OpenAIModel(private val config: AgentConfig, private val client: OpenAILLMClient, info: ModelInfo? = null,
    private val attachments: ((kinuvo.protocol.Attachment) -> ByteArray)? = null,
) : Model {
    private val model = (info ?: ModelInfo(config.model)).llModel(LLMProvider.OpenAI, listOf(LLMCapability.OpenAIEndpoint.Responses))

    override fun stream(instructions: String, history: List<SessionEntry>, tools: List<ToolDescriptor>): Flow<StreamFrame> {
        val params = OpenAIResponsesParams(
            store = false,
            include = listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT),
            parallelToolCalls = false,
            reasoning = if (config.reasoning == "none" || !model.supports(LLMCapability.Thinking)) null else ReasoningConfig(effort = effort(config.reasoning), summary = summary(config.reasoningSummary)),
            additionalProperties = mapOf("instructions" to JsonPrimitive(instructions)),
        )
        val p = prompt("kinuvo", params) {
            fun ai.koog.prompt.dsl.RequestMessagePartsBuilder.attach(list: List<kinuvo.protocol.Attachment>) = list.forEach { a ->
                val bytes = requireNotNull(attachments) { "This provider has no attachment storage" }(a)
                val content = AttachmentContent.Binary.Bytes(bytes)
                if (a.mimeType.startsWith("image/")) image(AttachmentSource.Image(content, a.mimeType.substringAfter('/'), a.mimeType, a.name))
                else file(AttachmentSource.File(content = content, format = a.name.substringAfterLast('.'), mimeType = a.mimeType, fileName = a.name))
            }
            // Function outputs carry text only, so files from tool results follow them as a user message.
            val shown = mutableListOf<kinuvo.protocol.Attachment>()
            fun flush() {
                if (shown.isEmpty()) return
                val files = shown.toList()
                shown.clear()
                user { text("Files from the tool results above:"); attach(files) }
            }
            for (entry in history) {
                if (entry !is SessionEntry.ToolResult) flush()
                when (entry) {
                    is SessionEntry.Context -> system(entry.content)
                    is SessionEntry.UserMessage -> user {
                        if (entry.modelText.isNotBlank()) text(entry.modelText)
                        attach(entry.attachments)
                    }
                    is SessionEntry.AssistantMessage -> assistant(entry.text)
                    is SessionEntry.ToolCall -> toolCall(MessagePart.Tool.Call(id = entry.id, tool = entry.name, args = entry.args))
                    is SessionEntry.ToolResult -> {
                        toolResult(MessagePart.Tool.Result(entry.id, entry.name, entry.output, entry.isError))
                        shown += entry.attachments
                    }
                    is SessionEntry.Reasoning -> if (entry.encrypted != null) reasoning(
                        MessagePart.Reasoning(content = emptyList(), summary = listOf(entry.text), encrypted = entry.encrypted, id = entry.id)
                    )
                    is SessionEntry.Compaction -> {
                        system("Summary of the earlier part of this conversation:\n\n${entry.summary}")
                        entry.activations.forEach { system(activationMessage(it)) }
                    }
                    is SessionEntry.Activation -> system(activationMessage(entry))
                    is SessionEntry.Started, is SessionEntry.Settings, is SessionEntry.Details, is SessionEntry.Reflection, is SessionEntry.ReflectionReview, is SessionEntry.Measurement -> Unit
                }
            }
            flush()
        }
        return client.executeStreaming(p, model, tools.sortedBy { it.name }).catch { throw friendly(it) }
    }

    private fun activationMessage(a: SessionEntry.Activation) = "Skill '${a.name}' is active in this conversation:\n\n${a.content}"

    private fun friendly(e: Throwable): Throwable {
        val status = generateSequence(e) { it.cause }.filterIsInstance<KoogHttpClientException>().firstOrNull()?.statusCode
        return when (status) {
            401, 403 -> if (config.provider == "openai") IllegalStateException("OpenAI rejected the API key ($status). Check OPENAI_API_KEY on the Server.", e) else IllegalStateException("Codex rejected the login ($status). Run `kinuvo login` or `codex login` and try again.", e)
            429 -> if (config.provider == "openai") IllegalStateException("OpenAI rate or usage limit reached (429). Check your API account limits.", e) else IllegalStateException("Codex usage limit reached (429). Check the usage indicator for when it resets.", e)
            null -> e
            else -> IllegalStateException("${if (config.provider == "openai") "OpenAI" else "Codex"} request failed ($status): ${e.message?.lineSequence()?.firstOrNull { it.startsWith("Message") } ?: ""}".trim(), e)
        }
    }

    private fun summary(value: String) =
        if (value.equals("none", ignoreCase = true)) null
        else ReasoningSummary.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ReasoningSummary.AUTO

    private fun effort(value: String) = ReasoningEffort.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: ReasoningEffort.MEDIUM
}
