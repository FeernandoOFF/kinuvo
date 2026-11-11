package kinuvo.server.jvm

import kinuvo.server.plugin.ProviderPlugin
import kinuvo.testing.harness.*
import ai.koog.prompt.message.Message

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.providers.codex.CodexAuth
import kinuvo.server.plugin.PrivateFile
import kinuvo.providers.codex.CodexProvider
import kinuvo.harness.builtin.AgentModel
import kinuvo.harness.AgentConfig
import kinuvo.protocol.agent.UpdateAgentParams
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kinuvo.server.Admin
import kinuvo.server.Runs
import kinuvo.server.providers.ModelProvider
import kinuvo.server.providers.ModelProviders

class SessionSettingsTest {
    private val home = Files.createTempDirectory("harness-settings").toFile()

    @Test
    fun modelPairsRouteAcrossPluginsAndSurviveRestart() = runBlocking {
        val used = mutableListOf<String>()
        fun plugin(id: String) = object : ProviderPlugin, TestModelProvider {
            override val definition = kinuvo.protocol.plugin.PluginDefinition("test.$id", "Test")
            override val id = id
            override val providerAdmins get() = listOf(this)
            override fun model(config: AgentConfig): AgentModel {
                used += "$id/${config.model}"
                return scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End()) }
            }
        }
        val plugins = listOf(plugin("first"), plugin("second"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var harness = Harness(Path(home.path), providers = ModelProviders.of(plugins), plugins = plugins)
        try {
            val admin = Admin(harness, Runs(harness.log, scope, harness.native)) {}
            admin.update(UpdateAgentParams(AgentId("default"), provider = "first", model = "shared-id"))
            val agent = harness.store.agents().single()
            val session = harness.newSession(agent).id
            harness.configure(agent, session, SessionSettings(provider = "second", model = "shared-id"))
            harness.runner(agent, session).run(session, "hi").toList()
            assertEquals("second/shared-id", used.last())
            assertEquals("first", harness.store.agents().single().config.provider)
            assertFailsWith<IllegalArgumentException> { harness.configure(agent, session, SessionSettings(provider = "missing", model = "custom")) }
            assertFailsWith<IllegalArgumentException> { harness.configure(agent, session, SessionSettings(provider = "second")) }
            harness.close()
            harness = Harness(Path(home.path), providers = ModelProviders.of(plugins), plugins = plugins)
            val restored = harness.store.agents().single()
            harness.runner(restored, session).run(session, "again").toList()
            assertEquals("second/shared-id", used.last())
            harness.configure(restored, session, SessionSettings())
            harness.runner(restored, session)
            assertEquals("first/shared-id", used.last())
        } finally { harness.close(); scope.cancel() }
    }

    @Test
    fun aSessionRunsWithItsOwnSettings(): Unit = runBlocking {
        val used = mutableListOf<String>()
        val seen = mutableListOf<List<Message>>()
        val harness = Harness(Path(home.path), providers = ModelProviders.of(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { config ->
            used += "${config.model}/${config.reasoning}/${config.approvals}"
            scriptedModel { prompt, _ -> seen += prompt.messages; flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End()) }
        }, plugins = emptyList())
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent).id

        val info = harness.configure(agent, session, SessionSettings(model = " gpt-mini ", approvals = "strict"))
        assertEquals(SessionSettings("gpt-mini", null, "strict"), info.settings)
        harness.runner(agent, session).run(session, "hi").toList()
        assertEquals("gpt-mini/medium/strict", used.last())
        assertEquals(listOf("hi"), seen.last().filterIsInstance<Message.User>().map { it.textContent() })

        harness.configure(agent, session, SessionSettings())
        harness.runner(agent, session)
        assertEquals("${agent.config.model}/medium/default", used.last())
        assertEquals(2, harness.store.entries(agent.id, session).count { it is SessionEntry.Settings })
        assertFailsWith<IllegalArgumentException> { harness.configure(agent, session, SessionSettings(approvals = "yolo")) }
    }
}
