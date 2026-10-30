package kinuvo.harness.server

import kinuvo.harness.testing.*

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.modelAlias
import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.InstructionSection
import kinuvo.harness.core.ModelProvider
import kinuvo.harness.core.PluginTools
import kinuvo.harness.core.RunContext
import kinuvo.harness.core.tools.Tool
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.*
import kinuvo.client.KinuvoClient
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginsTest {
    private val released = mutableListOf<String>()

    private val plugin = object : HarnessPlugin, ModelProvider {
        override val definition = kinuvo.protocol.PluginDefinition("test.pluginstest.1", "Test")
        override val id = "fake"
        override val modelProviders get() = listOf(this)
        override suspend fun models() = listOf(ModelInfo("plugin-model", "Plugin model"))
        override fun model(config: AgentConfig) = scriptedModel { _, tools ->
            flowOf(StreamFrame.TextDelta(tools.joinToString { it.name }), StreamFrame.End())
        }
        override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
            override val descriptor = ToolDescriptor("ping", "ping")
            override suspend fun execute(args: JsonObject) = "pong"
        }), listOf("half up"))
        override suspend fun instructions(agent: Agent) = InstructionSection("Fake", "Plugin section")
        override suspend fun release(agentId: String) { released += agentId }
    }

    @Test
    fun authenticatedClientsDiscoverInstalledPluginModels() = runBlocking {
        val harness = Harness(home = Path(Files.createTempDirectory("plugin-model-list").toString()), plugins = listOf(plugin))
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0, schedule = false)
        val client = KinuvoClient("ws://127.0.0.1:$port/ws", "test", pairingCode = server.auth.currentPairingCode())
        try {
            client.connect()
            val catalog = client.call(Api.Models, ModelsParams())
            assertEquals(listOf("fake"), catalog.providers.map { it.id })
            assertEquals("plugin-model", catalog.providers.single().models.single().id)
        } finally { client.close(); server.stop() }
    }

    @Test
    fun pluginsContributeModelsToolsAndInstructions() = runBlocking {
        val harness = Harness(home = Path(Files.createTempDirectory("harness-plugins").toString()), plugins = listOf(plugin))
        val agent = harness.store.agents().single().let { it.copy(config = it.config.copy(provider = "fake", tools = emptyList())) }
        val session = harness.newSession(agent)
        val started = harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.Started>().single()
        assertTrue("Plugin section" !in started.instructions)

        val events = harness.runner(agent, session.id).run(session.id, "hi").toList()
        assertTrue(harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.Context>().any { "## Fake\n\nPlugin section" in it.content })
        assertTrue(events.any { it is kinuvo.protocol.AgentEvent.Warning && it.message == "half up" })
        assertEquals(kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId(plugin.definition.id), "ping").modelAlias(), harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.AssistantMessage>().single().text)

        harness.release(agent)
        assertEquals(listOf(agent.id), released)
    }

    @Test
    fun unknownProviderIsReported() = runBlocking {
        val harness = Harness(home = Path(Files.createTempDirectory("harness-plugins").toString()), plugins = listOf(plugin))
        val agent = harness.store.agents().single()
        val error = runCatching { harness.runner(agent) }.exceptionOrNull()
        assertTrue("No model provider 'codex'" in error?.message.orEmpty())
    }
}
