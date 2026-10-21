package kinuvo.harness.core

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo

/** Koog's summarizer, with text chunks bounded even when one tool result exceeds the context window. */
internal class HistoryCompression(private val inputTokens: () -> Int) : HistoryCompressionStrategy() {
    override suspend fun compress(llmSession: AIAgentLLMWriteSession, memoryMessages: List<Message>) {
        val original = llmSession.prompt
        var remaining = original.messages.joinToString("\n\n") { message ->
            "${message.role}: " + message.parts.joinToString("\n") { part -> when (part) {
                is MessagePart.Text -> part.text
                is MessagePart.Tool.Call -> "Call ${part.tool} (${part.id}): ${part.args}"
                is MessagePart.Tool.Result -> "Result ${part.tool} (${part.id}, error=${part.isError}): ${part.output}"
                is MessagePart.Reasoning -> (part.summary ?: part.content).joinToString("\n")
                is MessagePart.Attachment -> "[Earlier attachment]"
            } }
        }
        var summary = emptyList<Message.Assistant>()
        // Reserve space for Koog's summary request and message framing.
        val chars = (inputTokens() - 512) * 4
        check(chars > 0) { "The model's context window is too small for Compaction" }
        do {
            val previous = summary.sumOf { it.textContent().length }
            val available = chars - previous
            check(available > 0) { "Compaction summary exceeds the model's context budget" }
            val chunk = remaining.take(available)
            remaining = remaining.drop(chunk.length)
            llmSession.prompt = original.withMessages { summary + Message.User(chunk, RequestMetaInfo.Empty) }
            summary = compressPromptIntoTLDR(llmSession)
        } while (remaining.isNotEmpty())
        llmSession.prompt = original.withMessages { summary }
    }
}
