package kinuvo.harness.server

import kinuvo.harness.execution.api.AgentConfig
import kinuvo.harness.core.AgentModel
import kinuvo.harness.core.plugin.ModelProvider
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileStore
import kinuvo.server.plugin.deleteTree
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

class ProviderIdTest {
    private fun provider(id: String) = object : ModelProvider {
        override val id = id
        override fun model(config: AgentConfig): AgentModel = error("Not used")
    }

    @Test fun aBareCustomKeyFindsItsPrefixedProviderUnlessABuiltInHasIt() {
        val runtime = RuntimeFixture(providers = listOf(provider("openai"), provider("custom/openai"), provider("custom/lmstudio"))).runtime()
        assertEquals("custom/lmstudio", runtime.providerId("lmstudio"))
        assertEquals("openai", runtime.providerId("openai"))
        assertEquals("custom/openai", runtime.providerId("custom/openai"))
        runtime.requireModelProvider("lmstudio")
        assertFails { runtime.requireModelProvider("missing") }
    }

    @Test fun agentsOfACustomProviderThatReplacedABuiltInOneKeepItOnce() = runBlocking {
        val files = AgentFiles(Path(SystemTemporaryDirectory, "kinuvo-test-${Random.nextLong().toULong().toString(16)}"))
        try {
            val store = FileStore(files)
            val agent = store.agents().single().let { store.saveConfig(it, it.config.copy(provider = "openai")) }
            val providers = listOf(provider("openai"), provider("custom/openai"))
            claimShadowedProviders(files, store, providers)
            assertEquals("custom/openai", store.agents().single().config.provider)
            store.saveConfig(agent, agent.config.copy(provider = "openai"))
            claimShadowedProviders(files, store, providers)
            assertEquals("openai", store.agents().single().config.provider)
        } finally { deleteTree(files.home) }
    }
}
