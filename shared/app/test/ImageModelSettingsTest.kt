package kinuvo.app

import kinuvo.app.agent.chatModels
import kinuvo.app.navigation.Route
import kinuvo.app.navigation.SettingsSection
import kinuvo.app.settings.SettingsState
import kinuvo.protocol.*
import kotlin.test.*

class ImageModelSettingsTest {
    @Test fun pickerOffersOnlyImageOutputModels() {
        val state = SettingsState(Route.Settings("agent", SettingsSection.Providers), models = ModelsCatalog(listOf(
            ProviderModels("openai", "OpenAI", listOf(ModelInfo("vision", input = listOf("image")), ModelInfo("image", output = listOf("image")))),
            ProviderModels("custom/router", "Router", listOf(ModelInfo("multimodal", output = listOf("text", "image")))),
        )))
        assertEquals(listOf(ProviderModelRef("openai", "image"), ProviderModelRef("custom/router", "multimodal")), state.imageModels)
    }

    @Test fun chatPickersSkipImageOnlyModels() {
        val group = ProviderModels("openai", "OpenAI", listOf(ModelInfo("gpt-image-1", output = listOf("image")), ModelInfo("gpt-5"), ModelInfo("multimodal", output = listOf("text", "image"))))
        assertEquals(listOf("gpt-5", "multimodal"), group.chatModels.map { it.id })
    }
}
