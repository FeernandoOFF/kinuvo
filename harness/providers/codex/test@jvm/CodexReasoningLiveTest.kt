package kinuvo.harness.codex

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentConfig
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class CodexReasoningLiveTest {
    @Test
    fun replaysReasoningAcrossTurns() {
        if (System.getenv("KINUVO_LIVE") != "1") return
        val model = OpenAIModel(AgentConfig(reasoning = "medium"), codexLLMClient(CodexAuth()))
        val history = mutableListOf<SessionEntry>(SessionEntry.UserMessage("How many primes are between 100 and 150? Answer with the number only."))
        runBlocking {
            val text = StringBuilder()
            model.stream("Think carefully.", history, emptyList()).collect {
                if (it is StreamFrame.ReasoningComplete) history += SessionEntry.Reasoning(it.summary.orEmpty().joinToString(), it.id, it.encrypted)
                if (it is StreamFrame.TextDelta) text.append(it.text)
            }
            history += SessionEntry.AssistantMessage(text.toString())
            history += SessionEntry.UserMessage("Now list them.")
            val second = StringBuilder()
            model.stream("Think carefully.", history, emptyList()).collect { if (it is StreamFrame.TextDelta) second.append(it.text) }
            assertTrue(history.any { it is SessionEntry.Reasoning && it.encrypted != null }, "no encrypted reasoning captured")
            assertTrue("149" in second, "got: $second")
        }
    }
}
