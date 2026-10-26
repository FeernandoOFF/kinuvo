package kinuvo.harness.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.llm.LLModel

/** Koog's default version map uses exact LLModel keys, while Kinuvo resolves model metadata dynamically. */
internal class AnthropicClient(private val http: KoogHttpClient) : AnthropicLLMClient(httpClient = http) {
    private fun client(model: LLModel) = AnthropicLLMClient(
        settings = AnthropicClientSettings(modelVersionsMap = mapOf(model to model.id)), httpClient = http,
    )
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = client(model).execute(prompt, model, tools)
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = client(model).executeStreaming(prompt, model, tools)
}
