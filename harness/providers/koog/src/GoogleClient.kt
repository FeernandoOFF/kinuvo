package kinuvo.harness.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.flow

/** Koog 1.3's streaming parser drops Google's function-call thought signatures. */
internal class GoogleClient(http: KoogHttpClient) : GoogleLLMClient(httpClient = http) {
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = flow {
        val response = execute(prompt, model, tools)
        response.parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> emit(StreamFrame.TextDelta(part.text))
                is MessagePart.Reasoning -> emit(StreamFrame.ReasoningComplete(part.id, part.content, part.summary, part.encrypted))
                is MessagePart.Tool.Call -> emit(StreamFrame.ToolCallComplete(part.id, part.tool, part.args))
                else -> Unit
            }
        }
        emit(StreamFrame.End(metaInfo = response.metaInfo))
    }
}
