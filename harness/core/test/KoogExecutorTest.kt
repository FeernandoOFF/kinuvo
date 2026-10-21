package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.testing.scriptedModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class KoogExecutorTest {
    private class Client(private val frames: () -> Flow<StreamFrame>) : LLMClient() {
        override fun llmProvider() = LLMProvider.OpenAI
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = frames()
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("Unused")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Unused")
        override fun close() {}
    }

    @Test
    fun retriesTransientFailuresBeforeStreamingButNeverDuplicatesPartialOutput() = runTest {
        val model = scriptedModel { _, _ -> error("Unused") }.model
        var attempts = 0
        var partial = false
        val executor = koogExecutor(Client { flow {
            attempts++
            if (partial) emit(StreamFrame.TextDelta("partial"))
            if (partial || attempts == 1) error("503 service unavailable")
            emit(StreamFrame.TextDelta("done"))
            emit(StreamFrame.End())
        } }, modelRetryConfig.copy(initialDelay = 1.milliseconds, maxDelay = 1.milliseconds, retryAfterExtractor = null))
        try {
            assertEquals("done", executor.executeStreaming(Prompt.Empty, model).toList().filterIsInstance<StreamFrame.TextDelta>().single().text)
            assertEquals(2, attempts)
            attempts = 0
            partial = true
            assertFailsWith<IllegalStateException> { executor.executeStreaming(Prompt.Empty, model).toList() }
            assertEquals(1, attempts)
        } finally { executor.close() }
    }

    @Test
    fun authenticationAndQuotaFailuresArePermanent() {
        val retry = modelRetryConfig.retryablePatterns.single()
        assertTrue(retry.matches("429 rate limit"))
        assertTrue(retry.matches("503 temporarily unavailable"))
        assertFalse(retry.matches("401 service unavailable"))
        assertFalse(retry.matches("429 insufficient_quota"))
        assertFalse(retry.matches("429 usage_limit exceeded"))
    }
}
