package kinuvo.harness.server

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.harness.execution.api.Agent
import kinuvo.harness.execution.api.AgentConfig
import kinuvo.harness.execution.api.AgentDoc
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.harness.core.plugin.*
import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.server.plugin.Tool
import kinuvo.protocol.ToolNames
import kinuvo.protocol.ToolGroup
import kinuvo.harness.core.tools.ToolScope
import kinuvo.protocol.*
import kinuvo.client.KinuvoClient
import kinuvo.harness.server.devices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class RunPreparationRuntimeTest {
    @Test fun basicProfileOmitsFrozenIdentityAndSoul() = runBlocking {
        val fixture = RuntimeFixture()
        fixture.documents[AgentDoc.Identity] = "IDENTITY_SENTINEL".repeat(1000)
        fixture.documents[AgentDoc.Soul] = "SOUL_SENTINEL".repeat(1000)
        var systems = ""
        val harness = fixture.runtime(scriptedModel { prompt, _ ->
            systems = prompt.messages.filterIsInstance<Message.System>().joinToString("\n") { it.textContent() }
            flowOf(StreamFrame.End())
        })
        try {
            val base = agent(harness)
            val agent = base.copy(config = base.config.copy(tier = ModelTier.Micro))
            val session = harness.newSession(agent)
            harness.runner(agent, session.id).run(session.id, "hello").toList()
            assertFalse("IDENTITY_SENTINEL" in systems)
            assertFalse("SOUL_SENTINEL" in systems)
            assertTrue("personal assistant" in systems)
        } finally { harness.close() }
    }

    @Test fun scopedCatalogToolRunsDirectlyOnMid() = runBlocking {
        var executed = false
        var requests = 0
        val plugin = object : HarnessPlugin {
            override val definition = PluginDefinition("test.scoped", "Scoped")
            override val catalogued = true
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
                override val descriptor = toolSpec("mcp_read", "Read", optionalParameters = listOf(
                    ToolParameter("nested", "Nested", ParameterType.List(ParameterType.List(ParameterType.String)))))
                override val risk = Risk.Low
                override suspend fun execute(args: JsonObject): String { executed = true; return "read" }
            }))
        }
        val provider = object : ModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools", tools = true))
            override fun model(config: AgentConfig) = scriptedModel { _, tools ->
                assertEquals(listOf("mcp_read"), tools.map { it.name })
                assertEquals(if (config.tier == ModelTier.Mid) 1 else 0, tools.single().optionalParameters.size)
                if (requests++ == 0) flowOf(StreamFrame.ToolCallComplete("read", "mcp_read", "{}"), StreamFrame.End())
                else flowOf(StreamFrame.End())
            }
        }
        val harness = harness(listOf(plugin), listOf(provider))
        try {
            val base = agent(harness)
            for (tier in listOf(ModelTier.Mid, ModelTier.Small, ModelTier.Micro)) {
                requests = 0
                executed = false
                val agent = base.copy(config = base.config.copy(tier = tier, approvals = "full"))
                val session = harness.newSession(agent)
                val events = harness.runner(agent, session.id, scope = ToolScope.only("mcp_read")).run(session.id, "read").toList()
                assertTrue(executed)
                assertTrue(events.none { it is AgentEvent.RunError })
            }
        } finally { harness.close() }
    }

    @Test fun authenticatedCatalogsEnableShowUiAndDisconnectRemovesThem() = runBlocking {
        val offered = mutableListOf<List<String>>()
        val fixture = RuntimeFixture()
        val harness = fixture.runtime(scriptedModel { _, tools ->
            offered += tools.map { it.name }
            flowOf(StreamFrame.End())
        })
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val responses = Channel<Frame>(Channel.UNLIMITED)
        fun connection(incoming: Channel<Frame>, outgoing: Channel<Frame>) = object : FrameConnection {
            override val incoming = incoming.receiveAsFlow()
            override suspend fun send(frame: Frame) { outgoing.send(frame) }
            override suspend fun close() { incoming.close(); outgoing.close() }
        }
        val peers = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun management(token: String) = false
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "test"
        }
        val server = ServerRuntime(harness, peers)
        val catalogs = listOf(UiCatalog("test", description = "x".repeat(30_000),
            components = mapOf("Text" to buildJsonObject { put("type", "object") })))
        val client = KinuvoClient("local:test", "Device", uiCatalogs = catalogs,
            connection = { connection(responses, requests) })
        server.start()
        val serving = launch { server.serve(connection(requests, responses)) }
        try {
            withTimeout(5_000) { client.connect() }
            assertEquals(catalogs, harness.uiCatalogs.forRun())
            val session = harness.newSession(fixture.agent)
            harness.runner(fixture.agent, session.id).run(session.id, "Show a form").toList()
            assertTrue("show_ui" in offered.last())
            client.close()
            withTimeout(5_000) { serving.join() }
            assertTrue(harness.uiCatalogs.forRun().isEmpty())
        } finally { client.close(); serving.cancelAndJoin(); server.close() }
    }

    @Test fun surfaceActionsPersistAndReachModelOnLaterTurns() = runBlocking {
        val fixture = RuntimeFixture()
        val prompts = mutableListOf<String>()
        val harness = fixture.runtime(scriptedModel { prompt, _ ->
            prompts += prompt.messages.filterIsInstance<Message.User>().joinToString("\n") { it.textContent() }
            flowOf(StreamFrame.End())
        })
        try {
            val session = harness.newSession(fixture.agent)
            val context = buildJsonObject { put("name", "Alex") }
            val action = SurfaceAction("call", "plan", "save", "submit", context, context)
            harness.runner(fixture.agent, session.id).run(session.id, "Save plan", action = action).toList()
            harness.runner(fixture.agent, session.id).run(session.id, "Continue").toList()
            assertEquals(action, harness.store.entries(fixture.agent.id, session.id).filterIsInstance<SessionEntry.UserMessage>().first().action)
            assertEquals(2, prompts.size)
            prompts.forEach {
                assertTrue("Save plan" in it)
                assertTrue("Surface action: save" in it)
                assertTrue("surface: plan" in it)
                assertTrue("Context: $context" in it)
            }
        } finally { harness.close() }
    }

    @Test fun surfaceToolsFollowRequestCatalogsAndActiveUnattachedFallback() = runBlocking {
        val offered = mutableListOf<List<String>>()
        val fixture = RuntimeFixture()
        val harness = fixture.runtime(scriptedModel { _, tools ->
            offered += tools.map { it.name }
            flowOf(StreamFrame.End())
        })
        try {
            val session = harness.newSession(fixture.agent)
            val catalogs = listOf(UiCatalog("test", components = mapOf("Text" to buildJsonObject { put("type", "object") })))
            harness.uiCatalogs.connect("app", catalogs)
            harness.uiCatalogs.connect("cli", emptyList())
            suspend fun run(connection: String?) {
                harness.runner(fixture.agent, session.id, catalogs = harness.uiCatalogs.forRun(connection))
                    .run(session.id, "Show a form").toList()
            }
            run("app")
            assertTrue("show_ui" in offered.last())
            run("cli")
            assertFalse("show_ui" in offered.last())
            harness.runner(fixture.agent, session.id).run(session.id, "Unattached run").toList()
            assertTrue("show_ui" in offered.last())
            harness.uiCatalogs.disconnect("app")
            harness.runner(fixture.agent, session.id).run(session.id, "Unattached run after disconnect").toList()
            assertFalse("show_ui" in offered.last())
        } finally { harness.close() }
    }

    @Test fun schedulingCannotBypassSessionApprovals() = runBlocking {
        val fixture = RuntimeFixture()
        val identity = ToolIdentity(PluginInstanceId("kinuvo.core"), "schedule")
        var args = buildJsonObject {
            put("action", "create"); put("name", "Reminder"); put("prompt", "Do work"); put("every", "1h")
        }
        val harness = fixture.runtime(scriptedModel { prompt, _ ->
            val history = prompt.messages
            if (history.toolResults.isNotEmpty()) flowOf(StreamFrame.End())
            else flowOf(StreamFrame.ToolCallComplete("schedule", identity.localId, args.toString()), StreamFrame.End())
        })
        try {
            val agent = fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf("schedule"), approvals = "relaxed"))
            suspend fun attempt(tolerance: String, decision: ApprovalDecision): PendingInteraction.Approval {
                val session = harness.newSession(agent)
                harness.configure(agent, session.id, SessionSettings(approvals = tolerance))
                val requests = mutableListOf<PendingInteraction.Approval>()
                harness.runner(agent, session.id).run(session.id, "schedule").collect { event ->
                    event.approval?.let { approval ->
                        requests += approval
                        assertTrue(harness.approvals.respond(approval.id, decision))
                    }
                }
                return requests.single()
            }
            val before = fixture.routines.list(agent)
            assertEquals(Risk.Medium, attempt("default", ApprovalDecision.Deny()).risk)
            assertEquals(before, fixture.routines.list(agent))
            attempt("default", ApprovalDecision.AllowOnce)
            assertTrue(fixture.routines.list(agent).single { it.name == "Reminder" }.enabled)

            val auto = fixture.routines.create(agent, "Auto", "Original prompt", Trigger.Every(60)) {
                it.copy(approvals = RoutineApprovals.Auto, enabled = false)
            }
            fixture.rules = TrustRules(trusted = listOf(identity.key))
            for (action in listOf("update", "resume", "run")) {
                args = buildJsonObject { put("action", action); put("id", auto.id); put("prompt", "Changed prompt") }
                val request = attempt("relaxed", ApprovalDecision.Deny())
                assertEquals(Risk.High, request.risk)
                assertFalse(request.trustable)
                assertEquals(auto, fixture.routines.get(agent, auto.id))
            }
        } finally { harness.close() }
    }

    @Test fun namedInstancesRouteSeparatelyAndUseStableEligibilityAndDenyRules() = runBlocking {
        val executed = mutableListOf<String>()
        fun plugin(instance: String) = object : HarnessPlugin {
            override val definition = PluginDefinition("example.ping", "Ping")
            override val instanceId = instance
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
                override val descriptor = toolSpec("ping", "Ping")
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
            override fun model(config: AgentConfig) = scriptedModel { prompt, tools ->
                val history = prompt.messages
                offered += tools.map { it.name }
                if (history.toolResults.isNotEmpty()) flowOf(StreamFrame.End())
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
            val results = harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>()
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
            fixture.rules = TrustRules(deny = DenyRules(tools = listOf("ping")))
            assertTrue(inspect(harness, agent).tools.all { it.state.authorization == Authorization.Denied })
            val deniedSession = harness.newSession(agent)
            harness.runner(agent, deniedSession.id).run(deniedSession.id, "ping").toList()
            assertEquals(listOf("second", "second"), executed)
            assertTrue(harness.store.entries(agent.id, deniedSession.id).filterIsInstance<SessionEntry.ToolResult>().all { it.isError })
        } finally { harness.close() }
    }

    @Test fun duplicateToolsCannotReplaceEachOtherDuringInspectionOrExecution() = runBlocking {
        val tool = object : Tool {
            override val descriptor = toolSpec("ping", "Ping")
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

    @Test fun skillCatalogFollowsTheAdvertisedActivateSkillTool() = runBlocking {
        val systems = mutableListOf<String>()
        fun provider(strip: Boolean) = object : ModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools", tools = true))
            override fun accepts(model: String, tool: ToolDescriptor) = !strip || tool.name != ToolNames.ACTIVATE_SKILL
            override fun model(config: AgentConfig) = scriptedModel { prompt, _ ->
                systems += prompt.messages.filterIsInstance<Message.System>().joinToString("\n") { it.textContent() }
                flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
            }
        }
        for (strip in listOf(false, true)) {
            val harness = harness(providers = listOf(provider(strip)))
            try {
                val agent = agent(harness, listOf(ToolNames.SKILLS))
                val session = harness.newSession(agent)
                harness.runner(agent, session.id).run(session.id, "hi").toList()
                assertEquals(!strip, "Current Skills:" in systems.last())
            } finally { harness.close() }
        }
    }

    @Test fun tierPolicyAndToolScopeNarrowWhatTheModelIsOffered() = runBlocking {
        val harness = harness()
        try {
            val base = agent(harness, listOf(ToolNames.MEMORY, ToolNames.SCHEDULE, ToolNames.ASK_USER))
            suspend fun offered(agent: Agent, scope: ToolScope = ToolScope.All): List<String> {
                val session = harness.newSession(agent)
                harness.runner(agent, session.id, scope = scope).run(session.id, "Remember that I like tea").toList()
                return seen.last().sorted()
            }
            assertEquals(listOf("ask_user", "memory", "schedule"), offered(base))
            assertEquals(listOf("memory"), offered(base.copy(config = base.config.copy(tier = ModelTier.Micro))))
            assertEquals(listOf("memory", "schedule"), offered(base.copy(config = base.config.copy(tier = ModelTier.Small))))
            assertEquals(listOf("ask_user"), offered(base, ToolScope.only("ask_user")))
            assertEquals(listOf("memory", "schedule"), offered(base.copy(config = base.config.copy(tier = ModelTier.Micro)), ToolScope.only("memory", "schedule")))
            assertEquals(listOf("memory"), offered(base.copy(config = base.config.copy(tier = ModelTier.Micro)), ToolScope.groups(ToolGroup.Core, ToolGroup.Data)))
        } finally { harness.close() }
    }

    @Test fun tierSetsThePromptStyle() = runBlocking {
        val systems = mutableListOf<String>()
        val harness = harness(providers = listOf(object : ModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools", tools = true))
            override fun model(config: AgentConfig) = scriptedModel { prompt, _ ->
                systems += prompt.messages.filterIsInstance<Message.System>().joinToString("\n") { it.textContent() }
                flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
            }
        }))
        try {
            val base = agent(harness)
            for (tier in listOf(ModelTier.Micro, ModelTier.Small, ModelTier.Frontier)) {
                val agent = base.copy(config = base.config.copy(tier = tier))
                val session = harness.newSession(agent)
                harness.runner(agent, session.id).run(session.id, "hi").toList()
            }
            assertTrue("Kinuvo capabilities for this run" !in systems[0] && "Kinuvo capabilities for this run" !in systems[1])
            assertTrue("Kinuvo capabilities for this run" in systems[2])
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
        override fun accepts(model: String, tool: ToolDescriptor) =
            tool.name != "ping" || tool.requiredParameters.any { p -> p.name == "message" }
        override fun model(config: AgentConfig): AgentModel {
            modelsBuilt++
            return scriptedModel { _, tools ->
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
                    override val descriptor = toolSpec("ping", "Ping", requiredParameters = listOf(
                        ToolParameter("message", "Message", ParameterType.String)))
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
            // Knowledge and Tasks follow their providers, which the fixture has, rather than the feature list.
            val offered = inspect(harness, agent, session.id).tools.mapNotNull { it.identity?.localId }.toSet()
            assertTrue("memory" in offered && "knowledge" in offered && "tasks" in offered)
            assertTrue(offered.none { it in setOf("activate_skill", "artifact", "schedule") })
            harness.runner(agent, session.id).run(session.id, "hi").toList()
            assertTrue("memory" in seen.last())
            assertFalse("schedule" in seen.last())
        } finally { harness.close() }
    }

    @Test fun runsInheritLatestAgentEffortAndRetainExplicitSessionEffort() = runBlocking {
        val efforts = mutableListOf<String>()
        val configured = object : ModelProvider by provider {
            override suspend fun models() = listOf(ModelInfo("tools", reasoning = listOf("low", "medium", "high"), defaultReasoning = "medium"))
            override fun model(config: AgentConfig, info: ModelInfo?): AgentModel {
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
