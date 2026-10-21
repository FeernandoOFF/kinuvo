package kinuvo.harness.codex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CodexModelsTest {
    private val catalog = """{"models":[
        {"slug":"dynamic","display_name":"Dynamic model","supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}],"default_reasoning_level":"low","context_window":1234,"input_modalities":["text","image"],"visibility":"list"},
        {"slug":"hidden","visibility":"hide"},
        {"slug":"new-model","supported_reasoning_levels":[]}
    ]}"""

    @Test
    fun reflectionPrefersTheSmallAvailableModel() {
        val provider = CodexProvider()
        fun model(id: String) = kinuvo.protocol.ModelInfo(id)
        assertEquals("gpt-6-luna", provider.reflectionModel(listOf(model("gpt-6"), model("gpt-6-luna"))))
        assertEquals("mini", provider.reflectionModel(listOf(model("big"), model("mini"))))
        assertNull(provider.reflectionModel(listOf(model("big"))))
        assertNull(provider.reflectionModel(listOf(model("gpt-6-luna").copy(state = kinuvo.protocol.CapabilityState.unavailable("offline")))))
    }

    @Test
    fun discoversWithoutAHardcodedModelList() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("models", request.url.encodedPath.substringAfterLast('/'))
            assertNotNull(request.url.parameters["client_version"])
            respond(catalog, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val models = discoverCodexModels(client)
            assertEquals(listOf("dynamic", "new-model"), models.map { it.id })
            assertEquals(listOf("low", "high"), models.first().reasoning)
            assertEquals("low", models.first().defaultReasoning)
            assertEquals(listOf("text", "image"), models.first().input)
            assertEquals(emptyList(), models.last().reasoning)
        } finally { client.close() }
    }

    @Test
    fun liveDiscovery() = runBlocking {
        if (System.getenv("KINUVO_LIVE_MODELS") != "1") return@runBlocking
        val provider = CodexProvider()
        try {
            val models = provider.models()
            assertTrue(models.isNotEmpty(), "Authenticated catalog returned no selectable models")
            println("Discovered ${models.size} Codex models")
        } finally { provider.close() }
    }
}
