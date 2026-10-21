package kinuvo.harness.server

import kinuvo.harness.testing.*

import kinuvo.harness.core.*
import kinuvo.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.uuid.Uuid
import kinuvo.harness.core.store.AgentFiles
import kotlin.test.*

class ModelCatalogTest {
    private val home = Path(SystemTemporaryDirectory, "model-catalog-${Uuid.random()}")
    @AfterTest
    fun cleanup() = kinuvo.harness.core.store.deleteTree(home)
    @Test
    fun runnerPassesResolvedMetadataToTheProvider() = runBlocking {
        val expected = ModelInfo("resolved", reasoning = emptyList(), input = listOf("text"), tools = false, context = 8192)
        var received: ModelInfo? = null
        val plugin = object : ModelProvider, HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.modelcatalogtest.1", "Test")
            override val id = "test"
            override val modelProviders get() = listOf(this)
            override suspend fun models() = listOf(expected)
            override fun model(config: AgentConfig): AgentModel = error("Metadata was not passed")
            override fun model(config: AgentConfig, info: ModelInfo?): AgentModel {
                received = info
                return scriptedModel { _, _ -> emptyFlow() }
            }
        }
        val harness = HarnessRuntime(ServerInstallation.assemble(AgentFiles(Path(home, "installation")), plugins = listOf(plugin)))
        try {
            val original = harness.store.agents().single()
            val agent = harness.store.saveConfig(original, AgentConfig(provider = "test", model = "resolved"))
            harness.runner(agent)
            assertEquals(expected, received)
        } finally { harness.close() }
    }

    @Test
    fun discoveryOwnsAvailabilityAndMetadataOnlyFillsMissingFields() = runBlocking {
        val file = Path(home, "catalog.json")
        val plugin = object : ModelProvider {
            override val id = "subscription"
            override val name = "Subscription"
            override val catalogId = "lab"
            override fun model(config: AgentConfig) = scriptedModel { _, _ -> emptyFlow() }
            override suspend fun models() = listOf(ModelInfo("available", reasoning = listOf("high"), context = 123), ModelInfo("new-private"))
        }
        val metadata = Json.parseToJsonElement("""{"lab":{"models":{
            "available":{"name":"Friendly","reasoning_options":[{"type":"effort","values":["low"]}],"limit":{"context":999},"modalities":{"input":["text","image"]}},
            "unavailable":{"name":"Never selectable"}
        }}}""").jsonObject
        val catalog = ModelCatalog(file, listOf(plugin), { metadata })
        val result = catalog.list().providers.single()
        assertEquals(listOf("available", "new-private"), result.models.map { it.id })
        assertEquals("Friendly", result.models.first().name)
        assertEquals(listOf("high"), result.models.first().reasoning)
        assertEquals(123, result.models.first().context)
        assertEquals(listOf("text", "image"), result.models.first().input)
        assertFalse(result.stale)
    }

    @Test
    fun cacheSurvivesRestartAndFailedRefreshWithoutLeakingProviderErrors() = runBlocking {
        val file = Path(home, "cache.json")
        var now = 1_000_000L
        var calls = 0
        var failing = false
        val plugin = object : ModelProvider {
            override val id = "local"
            override fun model(config: AgentConfig) = scriptedModel { _, _ -> emptyFlow() }
            override suspend fun models(): List<ModelInfo> {
                calls++
                if (failing) error("secret-provider-response")
                return listOf(ModelInfo("custom"))
            }
        }
        fun catalog() = ModelCatalog(file, listOf(plugin), { error("No metadata needed") }, { now })
        assertEquals("custom", catalog().list().providers.single().models.single().id)
        failing = true
        val restored = catalog()
        assertFalse(restored.list().providers.single().stale)
        assertEquals(1, calls)
        now += 3_600_001
        val stale = restored.list().providers.single()
        assertTrue(stale.stale)
        assertEquals("custom", stale.models.single().id)
        assertFalse("secret-provider-response" in stale.warning.orEmpty())
        restored.list()
        assertEquals(2, calls, "Failures are throttled")
        restored.list(refresh = true)
        assertEquals(3, calls, "Explicit refresh retries")
    }
}
