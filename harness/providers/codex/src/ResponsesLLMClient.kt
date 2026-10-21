package kinuvo.harness.codex

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kinuvo.harness.core.TokenUsageCapture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList

/** Codex requires SSE; complete calls also stream so both providers retain usage and timing. */
internal class ResponsesLLMClient(settings: OpenAIClientSettings, httpClient: KoogHttpClient) : OpenAILLMClient(
    settings = settings,
    httpClient = httpClient,
    toolsConverter = RawSchemaAwareGenerator(),
) {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val capture = currentCoroutineContext()[TokenUsageCapture]
        val frames = executeStreaming(prompt, model, tools).onEach { capture?.recordFrame?.invoke(it) }.toList()
        val text = if (frames.none { it is StreamFrame.TextComplete })
            listOf(StreamFrame.TextComplete(frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text }))
        else emptyList()
        return (frames + text).toMessageResponse()
    }
}
