package kinuvo.harness.core

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kinuvo.protocol.ModelInfo
import kotlin.test.*

class ModelInfoTest {
    @Test
    fun resolvedMetadataControlsTheClientCapabilitiesAndContext() {
        val text = ModelInfo("text", reasoning = emptyList(), input = listOf("text"), tools = false, context = 8192)
            .llModel(LLMProvider.OpenAI, listOf(LLMCapability.OpenAIEndpoint.Responses))
        assertEquals(8192L, text.contextLength)
        assertFalse(text.supports(LLMCapability.Tools))
        assertFalse(text.supports(LLMCapability.Thinking))
        assertFalse(text.supports(LLMCapability.Vision.Image))
        assertTrue(text.supports(LLMCapability.OpenAIEndpoint.Responses))
        val multimodal = ModelInfo("vision", reasoning = listOf("high"), input = listOf("text", "image", "file"), tools = true).llModel(LLMProvider.OpenAI)
        assertTrue(multimodal.supports(LLMCapability.Thinking))
        assertTrue(multimodal.supports(LLMCapability.Vision.Image))
        assertTrue(multimodal.supports(LLMCapability.Document))
    }
}
