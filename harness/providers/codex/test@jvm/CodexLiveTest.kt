package kinuvo.harness.codex

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class CodexLiveTest {
    @Test
    fun streamsAReply() {
        if (System.getenv("KINUVO_LIVE") != "1") return
        val client = codexLLMClient(CodexAuth())
        val params = OpenAIResponsesParams(
            store = false,
            reasoning = ReasoningConfig(effort = ReasoningEffort.LOW),
            additionalProperties = mapOf("instructions" to JsonPrimitive("You are a terse assistant.")),
        )
        val p = prompt("live", params) { user("Reply with exactly: pong") }
        val text = StringBuilder()
        runBlocking {
            client.executeStreaming(p, codexModel(System.getenv("KINUVO_MODEL") ?: "gpt-5.6-sol")).collect {
                println(it)
                if (it is StreamFrame.TextDelta) text.append(it.text)
            }
        }
        assertTrue(text.contains("pong", ignoreCase = true), "got: $text")
    }
}
