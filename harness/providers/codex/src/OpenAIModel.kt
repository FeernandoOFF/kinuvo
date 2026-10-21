package kinuvo.harness.codex

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openai.models.ReasoningSummary
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kinuvo.harness.core.*
import kinuvo.protocol.Attachment
import kinuvo.protocol.ModelInfo
import kotlinx.serialization.json.JsonPrimitive

fun openAIModel(config: AgentConfig, client: LLMClient, info: ModelInfo? = null,
    attachments: ((Attachment) -> ByteArray)? = null,
): AgentModel = openAIModel(config, koogExecutor(client), info, attachments)

fun openAIModel(config: AgentConfig, executor: PromptExecutor, info: ModelInfo? = null,
    attachments: ((Attachment) -> ByteArray)? = null,
): AgentModel {
    val model = (info ?: ModelInfo(config.model)).llModel(LLMProvider.OpenAI, listOf(LLMCapability.OpenAIEndpoint.Responses))
    return AgentModel(executor, model, parameters = { instructions ->
        OpenAIResponsesParams(
            store = false,
            include = listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT),
            parallelToolCalls = false,
            reasoning = if (config.reasoning == "none" || !model.supports(LLMCapability.Thinking)) null else ReasoningConfig(effort = effort(config.reasoning), summary = summary(config.reasoningSummary)),
            additionalProperties = mapOf("instructions" to JsonPrimitive(instructions)),
        )
    }, attachments = attachments, instructionsInPrompt = false, failure = { friendly(config, it) })
}

private fun friendly(config: AgentConfig, e: Throwable): Throwable {
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
