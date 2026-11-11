package kinuvo.protocol.model

import kotlin.test.*

class ImageModelResolutionTest {
    private val chat = ProviderModelRef("custom/chat", "multimodal")
    private val pinned = ProviderModelRef("openai", "gpt-image-1")
    private val best = ProviderModelRef("openai", "gpt-image-1.5")
    private val available = listOf(best, pinned, chat)

    @Test fun prefersChatModel() = assertEquals(chat, resolveImageModel(chat, pinned, available))
    @Test fun usesPinnedFallback() = assertEquals(pinned, resolveImageModel(null, pinned, available))
    @Test fun automaticUsesBestAvailable() = assertEquals(best, resolveImageModel(null, null, available))
    @Test fun skipsUnavailableChoices() = assertEquals(best, resolveImageModel(chat, pinned, listOf(best)))
    @Test fun emptyCatalogHasNoSelection() = assertNull(resolveImageModel(chat, pinned, emptyList()))
    @Test fun ranksImageFamilies() {
        val catalog = ModelsCatalog(listOf(ProviderModels("openai", "OpenAI",
            listOf("dall-e-2", "gpt-image-1-mini", "gpt-image-1", "gpt-image-1.5", "dall-e-3")
                .map { ModelInfo(it, output = listOf("image")) })))
        assertEquals(listOf("gpt-image-1.5", "gpt-image-1", "gpt-image-1-mini", "dall-e-3", "dall-e-2"), catalog.imageModels().map { it.model })
    }
}
