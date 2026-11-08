package kinuvo.harness.litert

import kinuvo.harness.core.ModelProfile
import kinuvo.protocol.ModelTier
import kotlinx.coroutines.test.runTest
import kinuvo.harness.core.AgentConfig
import java.nio.file.Files
import kotlin.test.*

class LiteRtToolsTest {
    private fun provider() = LiteRtProvider(Files.createTempDirectory("kinuvo-litert").toFile())

    @Test fun declaresTiersAndDoesNotSendTheSessionInstructions() = runTest {
        val provider = provider()
        assertEquals(mapOf("qwen3-0.6b" to ModelTier.Micro, "gemma-4-e2b" to ModelTier.Small, "gemma-4-e4b" to ModelTier.Small), localModels.associate { it.id to it.tier })
        localModels.forEach { assertFalse(provider.model(AgentConfig(provider = "local", model = it.id)).instructionsInPrompt) }
    }

    @Test fun capsToolsPerModel() {
        val provider = provider()
        fun cap(id: String, tier: ModelTier) = provider.profile(id, ModelProfile.of(tier)).tools.cap
        assertEquals(5, cap("qwen3-0.6b", ModelTier.Micro))
        assertEquals(8, cap("gemma-4-e2b", ModelTier.Small))
        assertEquals(10, cap("gemma-4-e4b", ModelTier.Small))
        assertEquals(ModelProfile.of(ModelTier.Mid).tools.cap, cap("gemma-4-e4b", ModelTier.Mid))
    }
}
