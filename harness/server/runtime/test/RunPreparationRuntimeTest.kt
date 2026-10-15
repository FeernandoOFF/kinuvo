package kinuvo.harness.server

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.Tool
import kinuvo.protocol.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class RunPreparationRuntimeTest {
    @Test fun namedInstancesRouteSeparatelyAndUseStableEligibilityAndDenyRules() = runBlocking {
        val executed = mutableListOf<String>()
        fun plugin(instance: String) = object : HarnessPlugin {
            override val definition = PluginDefinition("example.ping", "Ping")
            override val instanceId = instance
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
                override val descriptor = ToolDescriptor("ping", "Ping")
                override val risk = Risk.Low
                override suspend fun execute(args: JsonObject) = instance.also { executed += it }
            }))
        }
        val first = ToolIdentity(PluginInstanceId("example.ping", "first"), "ping")
        val second = ToolIdentity(PluginInstanceId("example.ping", "second"), "ping")
        val offered = mutableListOf<List<String>>()
        val provider = object : ModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools", tools = true), ModelInfo("limited", tools = true, toolIds = setOf(second.key)))
            override fun model(config: AgentConfig) = Model { _, history, tools ->
                offered += tools.map { it.name }
                if (history.any { it is SessionEntry.ToolResult }) flowOf(StreamFrame.End())
                else flowOf(*tools.mapIndexed { i, tool -> StreamFrame.ToolCallComplete("call-$i", tool.name, "{}") }.toTypedArray(), StreamFrame.End())
            }
        }
        val fixture = RuntimeFixture(listOf(plugin("first"), plugin("second")), listOf(provider))
        fixture.rules = TrustRules(deny = DenyRules(tools = listOf(first.key)))
        val harness = fixture.runtime()
        try {
            val agent = agent(harness)
            val catalog = inspect(harness, agent).tools
            assertEquals(setOf(first, second), catalog.map { it.identity }.toSet())
            assertEquals(2, catalog.map { it.id }.distinct().size)
            assertEquals(Authorization.Denied, catalog.single { it.identity == first }.state.authorization)
            val session = harness.newSession(agent)
            harness.runner(agent, session.id).run(session.id, "ping").toList()
            assertEquals(listOf("second"), executed)
            val results = harness.store.entries(agent, session.id).filterIsInstance<SessionEntry.ToolResult>()
            assertEquals(setOf(first, second), results.map { it.identity }.toSet())
            assertTrue(results.single { it.identity == first }.isError)
            val limited = agent.copy(config = agent.config.copy(model = "limited"))
            val limitedCatalog = inspect(harness, limited).tools
            assertEquals(Support.Unsupported, limitedCatalog.single { it.identity == first }.state.support)
            assertEquals(Support.Supported, limitedCatalog.single { it.identity == second }.state.support)
            val limitedSession = harness.newSession(limited)
            harness.runner(limited, limitedSession.id).run(limitedSession.id, "ping").toList()
            assertEquals(listOf(catalog.single { it.identity == second }.id), offered.last())
            assertEquals(listOf("second", "second"), executed)
        } finally { harness.close() }
    }

    @Test fun duplicateToolsCannotReplaceEachOtherDuringInspectionOrExecution() = runBlocking {
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("ping", "Ping")
            override suspend fun execute(args: JsonObject) = "pong"
        }
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.runpreparationruntimetest.2", "Test")
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(tool, tool))
        }
        val harness = harness(listOf(plugin))
        try {
            val agent = agent(harness)
            assertEquals("Duplicate tool names", assertFailsWith<IllegalArgumentException> { inspect(harness, agent) }.message)
            assertFailsWith<IllegalArgumentException> { harness.runner(agent) }
            assertEquals(0, modelsBuilt)
        } finally { harness.close() }
    }

    private val seen = mutableListOf<List<String>>()
    private var modelsBuilt = 0
    private val provider = object : ModelProvider {
        override val id = "test"
        override suspend fun models() = listOf(
            ModelInfo("tools", tools = true, context = 100_000),
            ModelInfo("text", tools = false),
            ModelInfo("limited", tools = true, toolIds = setOf(ToolIdentity(PluginInstanceId("test.runpreparationruntimetest.3"), "ping").key)),
        )
        override fun usableTools(model: String, tools: List<ToolDescriptor>) = tools.filter {
            it.name != ToolIdentity(PluginInstanceId("test.runpreparationruntimetest.3"), "ping").modelAlias() || it.requiredParameters.any { p -> p.name == "message" }
        }
        override fun model(config: AgentConfig): Model {
            modelsBuilt++
            return Model { _, _, tools ->
                seen += tools.map { it.name }
                flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
            }
        }
    }
    private fun harness(plugins: List<HarnessPlugin> = emptyList(), providers: List<ModelProvider> = listOf(provider)) =
        RuntimeFixture(plugins, providers).runtime()
    private suspend fun agent(harness: HarnessRuntime, tools: List<String> = emptyList()) = harness.store.agents().single().let {
        it.copy(config = it.config.copy(provider = "test", model = "tools", tools = tools))
    }
    private suspend fun inspect(harness: HarnessRuntime, agent: Agent, session: String? = null, settings: SessionSettings? = null) =
        harness.capabilities(agent, session, settings, ServerCapabilities(harness.serverId, harness.features))

    @Test
    fun sessionSettingsAndFullDescriptorsDetermineEligibilityInBothPaths() = runBlocking {
        val modes = mutableListOf<Boolean>()
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.runpreparationruntimetest.3", "Test")
            override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
                modes += activate
                assertEquals("strict", run.agent.config.approvals)
                return PluginTools(listOf(object : Tool {
                    override val descriptor = ToolDescriptor("ping", "Ping", requiredParameters = listOf(
                        ToolParameterDescriptor("message", "Message", ToolParameterType.String)))
                    override suspend fun execute(args: JsonObject) = "pong"
                }))
            }
        }
        val harness = harness(listOf(plugin))
        try {
            val agent = agent(harness)
            val session = harness.newSession(agent)
            for (model in listOf("tools", "text", "limited", "custom")) {
                harness.configure(agent, session.id, SessionSettings(model = model, approvals = "strict"))
                val capability = inspect(harness, agent, session.id).tools.single()
                assertEquals(if (model == "text") Support.Unsupported else if (model == "custom") Support.Unknown else Support.Supported, capability.state.support)
                harness.runner(agent, session.id).run(session.id, "hi").toList()
                assertEquals(if (model == "text") emptyList() else listOf(capability.id), seen.last())
            }
            assertEquals(listOf(false, true, false, true, false, true, false, true), modes)
        } finally { harness.close() }
    }

    @Test
    fun unavailableModelIsInspectedWithoutActivationAndCannotExecute() = runBlocking {
        var activated = false
        val unavailable = object : ModelProvider by provider {
            override suspend fun availability(model: String) = CapabilityState.unavailable("Model is not downloaded")
        }
        val plugin = object : HarnessPlugin {
            override val definition = kinuvo.protocol.PluginDefinition("test.runpreparationruntimetest.4", "Test")
            override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
                activated = activated || activate
                return PluginTools()
            }
        }
        val harness = harness(listOf(plugin), listOf(unavailable))
        try {
            val agent = agent(harness)
            assertEquals(Availability.Unavailable, inspect(harness, agent).chat.availability)
            assertFailsWith<IllegalStateException> { harness.runner(agent) }
            assertFalse(activated)
            assertEquals(0, modelsBuilt)
        } finally { harness.close() }
    }

    @Test
    fun missingServerFeaturesRemoveTheirToolsForInspectionAndExecution() = runBlocking {
        val harness = RuntimeFixture(providers = listOf(provider)).runtime(features = emptySet())
        try {
            val agent = agent(harness, listOf("knowledge", "tasks", "skills", "artifact", "schedule", "memory"))
            val session = harness.newSession(agent)
            assertEquals(listOf("memory"), inspect(harness, agent, session.id).tools.map { it.identity?.localId })
            harness.runner(agent, session.id).run(session.id, "hi").toList()
            assertEquals(listOf(ToolIdentity(PluginInstanceId("kinuvo.core"), "memory").modelAlias()), seen.last())
        } finally { harness.close() }
    }


    @Test fun runsInheritLatestAgentEffortAndRetainExplicitSessionEffort() = runBlocking {
        val efforts = mutableListOf<String>()
        val configured = object : ModelProvider by provider {
            override suspend fun models() = listOf(ModelInfo("tools", reasoning = listOf("low", "medium", "high"), defaultReasoning = "medium"))
            override fun model(config: AgentConfig, info: ModelInfo?): Model {
                efforts += config.reasoning
                return provider.model(config)
            }
        }
        val harness = harness(providers = listOf(configured))
        try {
            val original = agent(harness).let { it.copy(config = it.config.copy(reasoning = "medium")) }
            val inherited = harness.newSession(original)
            val explicit = harness.newSession(original)
            harness.configure(original, explicit.id, SessionSettings(reasoning = "low"))
            val updated = original.copy(config = original.config.copy(reasoning = "high"))
            val fresh = harness.newSession(updated)
            for (session in listOf(inherited, fresh, explicit)) {
                harness.runner(updated, session.id).run(session.id, "hi").toList()
            }
            assertEquals(listOf("high", "high", "low"), efforts)
        } finally { harness.close() }
    }

}
