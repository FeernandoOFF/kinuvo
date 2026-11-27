package kinuvo.server

import kinuvo.server.execution.NativeExecution
import kinuvo.server.rpc.serverRoutes
import kinuvo.server.routines.RoutineRun
import kinuvo.protocol.team.TeamId
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ModelProfile
import kinuvo.harness.RunKind
import kinuvo.harness.ToolSpec
import kinuvo.harness.requiredNames
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.AgentDoc
import kinuvo.server.plugin.ServerPlugin
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.harness.builtin.plugin.*
import kinuvo.server.plugin.*
import kinuvo.testing.harness.*
import ai.koog.prompt.message.Message

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.builtin.*
import kinuvo.server.plugin.Tool
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.tool.ToolGroup
import kinuvo.server.tools.ToolScope
import kinuvo.protocol.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.device.*
import kinuvo.protocol.computer.*
import kinuvo.protocol.model.*
import kinuvo.protocol.plugin.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.session.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.tool.*
import kinuvo.client.KinuvoClient
import kinuvo.server.devices.*
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
import kinuvo.server.providers.ModelProvider

class RunPreparationRuntimeTest {
    @Test fun proactivityRefreshesNewAndExistingFullSessions() = runBlocking {
        val fixture = RuntimeFixture()
        val prompts = mutableListOf<String>()
        val harness = fixture.runtime(scriptedModel { prompt, _ ->
            prompts += prompt.messages.filterIsInstance<Message.System>().joinToString("\n") { it.textContent() }
            flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
        })
        try {
            val agent = agent(harness).let { it.copy(config = it.config.copy(tier = ModelTier.Frontier)) }
            val legacy = "Legacy operating instructions"
            val existing = fixture.store.createSession(agent.id, legacy)
            fixture.store.append(agent.id, existing.id, SessionEntry.UserMessage("Earlier request"))
            fixture.store.append(agent.id, existing.id, SessionEntry.AssistantMessage("Earlier answer"))
            val fresh = harness.newSession(agent)
            for (session in listOf(fresh, existing)) {
                repeat(2) {
                    val context = harness.composer.compose(agent, session.id).context
                    assertEquals(1, context.split(PROACTIVITY_BRIEF).size - 1)
                    harness.composer.runner(agent, session.id).run(session.id, "Continue").toList()
                    assertEquals(1, prompts.last().split(PROACTIVITY_BRIEF).size - 1)
                }
            }
            assertTrue(legacy in prompts.last())
            assertEquals(legacy, fixture.store.entries(agent.id, existing.id).filterIsInstance<SessionEntry.Started>().single().instructions)
            for (tier in listOf(ModelTier.NoTools, ModelTier.Micro, ModelTier.Small, ModelTier.Mid)) {
                val context = harness.composer.compose(agent.copy(config = agent.config.copy(tier = tier)), existing.id).context
                assertEquals(if (tier == ModelTier.Mid) 1 else 0, context.split(PROACTIVITY_BRIEF).size - 1)
            }
        } finally { harness.close() }
    }

    @Test fun firstSessionDiscoveryUsesCurrentDocumentsAndSessionHistory() = runBlocking {
        val local = object : ModelProvider by provider {
            override suspend fun models() = provider.models().map { it.copy(location = ExecutionLocation.ServerHost) }
        }
        val fixture = RuntimeFixture(providers = listOf(local))
        val harness = fixture.runtime()
        try {
            val agent = agent(harness).let { it.copy(config = it.config.copy(tier = ModelTier.Frontier)) }
            val session = harness.newSession(agent)
            suspend fun context() = harness.composer.compose(agent, session.id, discovery = true).context
            assertTrue("## First session" in context())
            assertFalse("## First session" in fixture.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.Started>().single().instructions)
            for (tier in listOf(ModelTier.NoTools, ModelTier.Micro, ModelTier.Small, ModelTier.Mid)) {
                assertFalse("## First session" in harness.composer.compose(agent.copy(config = agent.config.copy(tier = tier)), session.id, discovery = true).context)
            }
            for (doc in listOf(AgentDoc.Memory, AgentDoc.UserNotes)) {
                fixture.documents[doc] = "Known fact"
                assertFalse("## First session" in context())
                fixture.documents[doc] = " \n "
                assertTrue("## First session" in context())
            }
            fixture.store.append(agent.id, session.id, SessionEntry.UserMessage("Hello"))
            assertFalse("## First session" in context())
            fixture.store.append(agent.id, session.id, SessionEntry.AssistantMessage("Hello"))
            assertFalse("## First session" in context())
            val core = PluginInstanceId("kinuvo.core")
            val outputs = listOf(
                SessionEntry.UserMessage("Earlier failed or queued turn"),
                SessionEntry.VoiceCall("user", listOf(VoiceTurn(true, "Hello")), 0, 100),
                SessionEntry.ToolResult(ToolCallId("failed"), ToolNames.SHOW_UI, "Failed", isError = true),
                SessionEntry.ToolResult(ToolCallId("image"), "generate_image", "Image", attachments = listOf(Attachment("image", "image.png", "image/png", 8))),
                SessionEntry.ToolResult(ToolCallId("app"), "mcp_app", "Shown", identity = ToolIdentity(PluginInstanceId("kinuvo.mcp", "app"), "show")),
                SessionEntry.VoiceCall("voice", listOf(VoiceTurn(false, "Hello")), 0, 100),
            ) + listOf(ToolNames.SHOW_UI, ToolNames.SHOW_HTML, ToolNames.ASK_USER).map {
                SessionEntry.ToolResult(ToolCallId(it), "aliased_$it", "Shown", identity = ToolIdentity(core, it))
            }
            for (output in outputs) {
                val prior = harness.newSession(agent)
                fixture.store.append(agent.id, prior.id, output)
                assertFalse("## First session" in harness.composer.compose(agent, prior.id, discovery = true).context)
            }
            val next = harness.newSession(agent)
            assertTrue("## First session" in harness.composer.compose(agent, next.id, discovery = true).context)
            fixture.store.append(agent.id, next.id, SessionEntry.Details(hidden = true))
            assertFalse("## First session" in harness.composer.compose(agent, next.id, discovery = true).context)
            val ref = RoutineRef("r", "Routine")
            val scheduled = fixture.store.createSession(agent.id, "", ref)
            assertFalse("## First session" in harness.composer.compose(agent, scheduled.id, discovery = true).context)
            val team = harness.newSession(agent)
            fixture.store.append(agent.id, team.id, SessionEntry.Details(teamId = TeamId("team")))
            assertFalse("## First session" in harness.composer.compose(agent, team.id, discovery = true).context)
            val normal = harness.newSession(agent)
            assertFalse("## First session" in harness.composer.compose(agent, normal.id).context)
            assertFalse("## First session" in harness.composer.compose(agent, normal.id, taskId = "task", discovery = true).context)
            assertFalse("## First session" in harness.composer.compose(agent, normal.id, requireModel = false, discovery = true).context)
            val routine = RoutineRun(Routine("r", agent.id, "Routine", "Check", Trigger.Every(60), "UTC")) {}
            assertFalse("## First session" in harness.composer.compose(agent, normal.id, routine = routine, discovery = true).context)
            assertFalse("## First session" in harness.composer.compose(agent).context)
        } finally { harness.close() }
    }

    @Test fun sharedRunPathsRequireInteractiveDiscoveryOptIn() = runBlocking {
        val frontier = object : ModelProvider by provider {
            override fun profile(model: String, base: ModelProfile) = ModelProfile.of(ModelTier.Frontier)
        }
        val fixture = RuntimeFixture(providers = listOf(frontier))
        val prompts = mutableListOf<String>()
        val harness = fixture.runtime(scriptedModel { prompt, _ ->
            prompts += prompt.messages.joinToString("\n") { it.toString() }
            flowOf(StreamFrame.End())
        })
        try {
            val agent = fixture.agent
            val session = harness.newSession(agent)
            suspend fun context(kind: RunKind) = harness.composer.run(agent.id, session.id, ExecutionInput("Hello", kind = kind)).context
            assertTrue("## First session" in context(RunKind.Chat(discovery = true)))
            assertFalse("Only after an explicit decline" in context(RunKind.Chat(discovery = true)))
            // Workflow Agent turns use the default ExecutionInput kind.
            assertFalse("## First session" in harness.composer.run(agent.id, session.id, ExecutionInput("Workflow")).context)
            assertFalse("## First session" in context(RunKind.Chat(taskId = "task")))
            assertFalse("## First session" in context(RunKind.Chat(report = SessionEntry.ToolReport("tool", null, "Done"))))
            assertFalse("## First session" in context(RunKind.Chat(discovery = true, report = SessionEntry.ToolReport("tool", null, "Done"))))
            val routine = RoutineRun(Routine("r", agent.id, "Routine", "Check", Trigger.Every(60), "UTC")) {}
            assertFalse("## First session" in context(RunKind.Routine(routine)))
            assertFalse("## First session" in harness.composer.completion(agent, null, null).context)
            harness.composer.runner(agent, session.id).run(session.id, "Background").toList()
            assertTrue(prompts.isNotEmpty())
            assertTrue(prompts.none { "## First session" in it })
        } finally { harness.close() }
    }

    @Test fun nativeDispatchDiscoversOnlyOptedInInteractiveChat() = runBlocking {
        val frontier = object : ModelProvider by provider {
            override fun profile(model: String, base: ModelProfile) = ModelProfile.of(ModelTier.Frontier)
        }
        val fixture = RuntimeFixture(providers = listOf(frontier))
        val prompts = mutableListOf<String>()
        val harness = fixture.runtime(scriptedModel { prompt, _ ->
            prompts += prompt.messages.joinToString("\n") { it.toString() }
            flowOf(StreamFrame.End())
        })
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val runs = Runs(harness.log, scope, harness.native)
        val native = NativeExecution(harness, runs, scope, { fixture.agent }, { ServerCapabilities("test") }, { _, _ -> }, { _, _ -> })
        try {
            val agent = fixture.agent
            for (source in listOf("remote grant", "ACP gateway", "automated paired client", "Live voice delegation", "interactive chat")) {
                val session = harness.newSession(agent)
                val params = SendParams(agent.id, session.id, source, voice = source == "Live voice delegation", discovery = source == "interactive chat")
                prompts.clear()
                native.send(params)
                runs.join(session.id)
                assertTrue(prompts.isNotEmpty(), source)
                assertEquals(source == "interactive chat", prompts.any { "## First session" in it }, source)
                assertEquals(1, fixture.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.UserMessage>().size)
                prompts.clear()
                native.send(params.copy(text = "next", discovery = true))
                runs.join(session.id)
                assertTrue(prompts.none { "## First session" in it }, source)
            }
        } finally { scope.cancel(); scope.coroutineContext.job.join(); harness.close() }
    }

    @Test fun offlineDeviceToolsStayOfferedWhileComputerNeedsSetup() = runBlocking {
        val harness = harness()
        try {
            harness.devices.paired = { listOf(ToolDevice("phone", "Phone",
                setOf(DeviceToolKind.Contacts, DeviceToolKind.Sleep, DeviceToolKind.Workouts))) }
            val base = agent(harness, listOf("device_tools", "computer"))
            val session = harness.newSession(base)
            for (tier in listOf(ModelTier.Small, ModelTier.Frontier)) {
                val agent = base.copy(config = base.config.copy(tier = tier))
                val run = harness.composer.compose(agent, session.id)
                val names = run.tools.advertised.map { it.name }
                assertTrue(names.containsAll(listOf("contacts_lookup", "sleep_sessions", "workouts_list")))
                assertFalse(names.any { it.startsWith("computer_") })
                val capabilities = inspect(harness, agent, session.id.value)
                assertEquals("device_offline", capabilities.tools.first { it.id == "contacts_lookup" }.state.reasonCode)
                assertEquals("needs_setup", capabilities.tools.first { it.id == "computer_capture" }.state.reasonCode)
            }
        } finally { harness.close() }
    }

    @Test fun largeTextStagingNeedsAUsableShellTool() = runBlocking {
        val exec = object : ServerPlugin {
            override val definition = PluginDefinition("kinuvo.exec", "Shell")
            override val attachmentStager = AttachmentStager { _, _, _ -> "/ws/f" }
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
                override val descriptor = toolSpec(ToolNames.SHELL, "Run")
                override val risk = Risk.Low
                override suspend fun execute(args: JsonObject) = ""
            }))
        }
        val fixture = RuntimeFixture(listOf(exec), listOf(provider))
        val harness = fixture.runtime()
        try {
            val agent = agent(harness).let { it.copy(config = it.config.copy(exec = "host")) }
            assertTrue(inspect(harness, agent).stagesLargeText)
            fixture.rules = TrustRules(deny = DenyRules(tools = listOf(ToolIdentity(PluginInstanceId("kinuvo.exec"), ToolNames.SHELL).key)))
            assertFalse(inspect(harness, agent).stagesLargeText)
            fixture.rules = TrustRules()
            assertFalse(inspect(harness, agent.copy(config = agent.config.copy(model = "text"))).stagesLargeText)
            assertFalse(inspect(harness, agent.copy(config = agent.config.copy(tier = ModelTier.NoTools))).stagesLargeText)
        } finally { harness.close() }
    }

    @Test fun instructionOnlyPluginKeepsItsSection() = runBlocking {
        val plugin = object : ServerPlugin {
            override val definition = PluginDefinition("test.instructions", "Instructions")
            override suspend fun instructions(agent: Agent) = InstructionSection("Instructions", "Keep this guidance.")
        }
        val harness = harness(listOf(plugin))
        try {
            val agent = agent(harness)
            val session = harness.newSession(agent)
            assertTrue("## Instructions\n\nKeep this guidance." in harness.composer.compose(agent, session.id).context)
        } finally { harness.close() }
    }

    @Test fun computerReadinessControlsToolsAndPromptInTheSameRun() = runBlocking {
        val harness = harness()
        try {
            val agent = agent(harness, listOf("computer"))
            val session = harness.newSession(agent)
            val missing = harness.composer.compose(agent, session.id)
            assertFalse(missing.tools.advertised.any { it.name.startsWith("computer_") })
            assertFalse("## Computer use" in missing.context)
            assertTrue("Not set up: Computer use (guides/computer-use)" in missing.context)
            assertFalse("fetch its page" in missing.context)
            val missingMid = harness.composer.compose(agent.copy(config = agent.config.copy(tier = ModelTier.Mid)), session.id)
            assertTrue("Not set up: Computer use (guides/computer-use)" in missingMid.context)
            harness.computers.paired = { listOf(ToolDevice("mac", "Mac", setOf(DeviceToolKind.Computer))) }
            harness.computers.connect("mac") { ComputerResult(DeviceToolStatus.Ready) }
            val ready = harness.composer.compose(agent, session.id)
            assertTrue(ready.tools.advertised.any { it.name == "computer_capture" })
            assertTrue("## Computer use" in ready.context)
            assertFalse("Not set up:" in ready.context)
            val mid = harness.composer.compose(agent.copy(config = agent.config.copy(tier = ModelTier.Mid)), session.id)
            assertFalse("## About Kinuvo" in mid.context)
            assertTrue("Never claim success without a tool result" in mid.context)
        } finally { harness.close() }
    }

    @Test fun deniedComputerToolStillNeedsSetupWhenNoComputerIsPaired() = runBlocking {
        val fixture = RuntimeFixture(providers = listOf(provider))
        val capture = ToolIdentity(PluginInstanceId("kinuvo.computer"), "computer_capture")
        fixture.rules = TrustRules(deny = DenyRules(tools = listOf(capture.key)))
        val harness = fixture.runtime()
        try {
            val agent = agent(harness, listOf("computer"))
            val session = harness.newSession(agent)
            val run = harness.composer.compose(agent, session.id)
            assertFalse(run.tools.advertised.any { it.name.startsWith("computer_") })
            assertTrue("Not set up: Computer use (guides/computer-use)" in run.context)
        } finally { harness.close() }
    }

    @Test fun steeringAppearsAfterToolResultsBeforeTheNextModelRequest() = runBlocking {
        var executed = false
        var requests = 0
        var taken = false
        val plugin = object : ServerPlugin {
            override val definition = PluginDefinition("test.steering", "Steering")
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
                override val descriptor = toolSpec("read", "Read")
                override val risk = Risk.Low
                override suspend fun execute(args: JsonObject): String { executed = true; return "done" }
            }))
        }
        val provider = object : TestModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools", tools = true))
            override fun model(config: AgentConfig) = scriptedModel { prompt, _ ->
                if (requests++ == 0) flowOf(StreamFrame.ToolCallComplete("call", "read", "{}"), StreamFrame.End())
                else {
                    assertTrue(executed)
                    assertEquals("adjust", prompt.messages.filterIsInstance<Message.User>().last().textContent())
                    flowOf(StreamFrame.End())
                }
            }
        }
        val runtime = harness(listOf(plugin), listOf(provider))
        try {
            val agent = agent(runtime).let { it.copy(config = it.config.copy(approvals = "full")) }
            val session = runtime.newSession(agent)
            val events = runtime.composer.runner(agent, session.id).run(session.id, "first", steering = {
                if (executed && !taken) { taken = true; listOf(SessionEntry.UserMessage("adjust")) } else emptyList()
            }).toList()
            assertTrue(taken)
            assertEquals(2, requests)
            assertTrue(events.none { it is AgentEvent.RunError })
            val entries = runtime.store.entries(agent.id, session.id)
            assertTrue(entries.indexOfLast { it is SessionEntry.ToolResult } < entries.indexOfLast { it is SessionEntry.UserMessage })
        } finally { runtime.close() }
    }

    @Test fun externalPermissionToleranceFollowsSessionSettings() = runBlocking {
        val fixture = RuntimeFixture()
        val runtime = fixture.runtime()
        try {
            val agent = fixture.agent.copy(config = fixture.agent.config.copy(approvals = "full"))
            val session = runtime.newSession(agent)
            assertTrue(runtime.composer.autoApproveHarness(agent, session.id, RunKind.Chat()))
            for (tolerance in listOf("strict", "default", "relaxed", "full")) {
                runtime.configure(agent, session.id, SessionSettings(approvals = tolerance))
                assertEquals(tolerance == "full", runtime.composer.autoApproveHarness(agent, session.id, RunKind.Chat()))
            }
        } finally { runtime.close() }
    }

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
            harness.composer.runner(agent, session.id).run(session.id, "hello").toList()
            assertFalse("IDENTITY_SENTINEL" in systems)
            assertFalse("SOUL_SENTINEL" in systems)
            assertTrue("personal assistant" in systems)
        } finally { harness.close() }
    }

    @Test fun scopedCatalogToolRunsDirectlyOnMid() = runBlocking {
        var executed = false
        var requests = 0
        val plugin = object : ServerPlugin {
            override val definition = PluginDefinition("test.scoped", "Scoped")
            override val catalogued = true
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
                override val descriptor = toolSpec("mcp_read", "Read", optionalParameters = listOf(
                    ToolParameter("nested", "Nested", ParameterType.List(ParameterType.List(ParameterType.String)))))
                override val risk = Risk.Low
                override suspend fun execute(args: JsonObject): String { executed = true; return "read" }
            }))
        }
        val provider = object : TestModelProvider {
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
                val events = harness.composer.runner(agent, session.id, scope = ToolScope.only("mcp_read")).run(session.id, "read").toList()
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
        val server = ServerRuntime(harness, peers, serverRoutes())
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
            harness.composer.runner(fixture.agent, session.id).run(session.id, "Show a form").toList()
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
            val action = SurfaceAction(ToolCallId("call"), "plan", "save", "submit", context, context)
            harness.composer.runner(fixture.agent, session.id).run(session.id, "Save plan", action = action).toList()
            harness.composer.runner(fixture.agent, session.id).run(session.id, "Continue").toList()
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
                harness.composer.runner(fixture.agent, session.id, catalogs = harness.uiCatalogs.forRun(connection))
                    .run(session.id, "Show a form").toList()
            }
            run("app")
            assertTrue("show_ui" in offered.last())
            run("cli")
            assertFalse("show_ui" in offered.last())
            harness.composer.runner(fixture.agent, session.id).run(session.id, "Unattached run").toList()
            assertTrue("show_ui" in offered.last())
            harness.uiCatalogs.disconnect("app")
            harness.composer.runner(fixture.agent, session.id).run(session.id, "Unattached run after disconnect").toList()
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
                harness.composer.runner(agent, session.id).run(session.id, "schedule").collect { event ->
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
        fun plugin(instance: String) = object : ServerPlugin {
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
        val provider = object : TestModelProvider {
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
            harness.composer.runner(agent, session.id).run(session.id, "ping").toList()
            assertEquals(listOf("second"), executed)
            val results = harness.store.entries(agent.id, session.id).filterIsInstance<SessionEntry.ToolResult>()
            assertEquals(setOf(first, second), results.map { it.identity }.toSet())
            assertTrue(results.single { it.identity == first }.isError)
            val limited = agent.copy(config = agent.config.copy(model = "limited"))
            val limitedCatalog = inspect(harness, limited).tools
            assertEquals(Support.Unsupported, limitedCatalog.single { it.identity == first }.state.support)
            assertEquals(Support.Supported, limitedCatalog.single { it.identity == second }.state.support)
            val limitedSession = harness.newSession(limited)
            harness.composer.runner(limited, limitedSession.id).run(limitedSession.id, "ping").toList()
            assertEquals(listOf(catalog.single { it.identity == second }.id), offered.last())
            assertEquals(listOf("second", "second"), executed)
            fixture.rules = TrustRules(deny = DenyRules(tools = listOf("ping")))
            assertTrue(inspect(harness, agent).tools.all { it.state.authorization == Authorization.Denied })
            val deniedSession = harness.newSession(agent)
            harness.composer.runner(agent, deniedSession.id).run(deniedSession.id, "ping").toList()
            assertEquals(listOf("second", "second"), executed)
            assertTrue(harness.store.entries(agent.id, deniedSession.id).filterIsInstance<SessionEntry.ToolResult>().all { it.isError })
        } finally { harness.close() }
    }

    @Test fun duplicateToolsCannotReplaceEachOtherDuringInspectionOrExecution() = runBlocking {
        val tool = object : Tool {
            override val descriptor = toolSpec("ping", "Ping")
            override suspend fun execute(args: JsonObject) = "pong"
        }
        val plugin = object : ServerPlugin {
            override val definition = kinuvo.protocol.plugin.PluginDefinition("test.runpreparationruntimetest.2", "Test")
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(tool, tool))
        }
        val harness = harness(listOf(plugin))
        try {
            val agent = agent(harness)
            assertEquals("Duplicate tool names", assertFailsWith<IllegalArgumentException> { inspect(harness, agent) }.message)
            assertFailsWith<IllegalArgumentException> { harness.composer.runner(agent) }
            assertEquals(0, modelsBuilt)
        } finally { harness.close() }
    }

    @Test fun skillCatalogFollowsTheAdvertisedActivateSkillTool() = runBlocking {
        val systems = mutableListOf<String>()
        fun provider(strip: Boolean) = object : TestModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools", tools = true))
            override fun accepts(model: String, tool: ToolSpec) = !strip || tool.name != ToolNames.ACTIVATE_SKILL
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
                harness.composer.runner(agent, session.id).run(session.id, "hi").toList()
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
                harness.composer.runner(agent, session.id, scope = scope).run(session.id, "Remember that I like tea").toList()
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
        val harness = harness(providers = listOf(object : TestModelProvider {
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
                harness.composer.runner(agent, session.id).run(session.id, "hi").toList()
            }
            assertTrue("Kinuvo capabilities for this run" !in systems[0] && "Kinuvo capabilities for this run" !in systems[1])
            assertTrue("Kinuvo capabilities for this run" in systems[2])
        } finally { harness.close() }
    }

    private val seen = mutableListOf<List<String>>()
    private var modelsBuilt = 0
    private val provider = object : TestModelProvider {
        override val id = "test"
        override suspend fun models() = listOf(
            ModelInfo("tools", tools = true, context = 100_000),
            ModelInfo("text", tools = false),
            ModelInfo("limited", tools = true, toolIds = setOf(ToolIdentity(PluginInstanceId("test.runpreparationruntimetest.3"), "ping").key)),
        )
        override fun accepts(model: String, tool: ToolSpec) =
            tool.name != "ping" || "message" in tool.requiredNames
        override fun model(config: AgentConfig): AgentModel {
            modelsBuilt++
            return scriptedModel { _, tools ->
                seen += tools.map { it.name }
                flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
            }
        }
    }
    private fun harness(plugins: List<ServerPlugin> = emptyList(), providers: List<ModelProvider> = listOf(provider)) =
        RuntimeFixture(plugins, providers).runtime()
    private suspend fun agent(harness: AgentRuntime, tools: List<String> = emptyList()) = harness.store.agents().single().let {
        it.copy(config = it.config.copy(provider = "test", model = "tools", tools = tools))
    }
    private suspend fun inspect(harness: AgentRuntime, agent: Agent, session: String? = null, settings: SessionSettings? = null) =
        harness.composer.capabilities(agent, session?.let(::SessionId), settings, ServerCapabilities(harness.serverId, harness.features))

    @Test
    fun sessionSettingsAndFullDescriptorsDetermineEligibilityInBothPaths() = runBlocking {
        val modes = mutableListOf<Boolean>()
        val plugin = object : ServerPlugin {
            override val definition = kinuvo.protocol.plugin.PluginDefinition("test.runpreparationruntimetest.3", "Test")
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
                val capability = inspect(harness, agent, session.id.value).tools.single()
                assertEquals(if (model == "text") Support.Unsupported else if (model == "custom") Support.Unknown else Support.Supported, capability.state.support)
                harness.composer.runner(agent, session.id).run(session.id, "hi").toList()
                assertEquals(if (model == "text") emptyList() else listOf(capability.id), seen.last())
            }
            assertEquals(listOf(false, true, false, true, false, true, false, true), modes)
        } finally { harness.close() }
    }

    @Test
    fun unavailableModelIsInspectedWithoutActivationAndCannotExecute() = runBlocking {
        var activated = false
        val unavailable = object : TestModelProvider by provider {
            override suspend fun availability(model: String) = CapabilityState.unavailable("Model is not downloaded")
        }
        val plugin = object : ServerPlugin {
            override val definition = kinuvo.protocol.plugin.PluginDefinition("test.runpreparationruntimetest.4", "Test")
            override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
                activated = activated || activate
                return PluginTools()
            }
        }
        val harness = harness(listOf(plugin), listOf(unavailable))
        try {
            val agent = agent(harness)
            assertEquals(Availability.Unavailable, inspect(harness, agent).chat.availability)
            assertFailsWith<IllegalStateException> { harness.composer.runner(agent) }
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
            val offered = inspect(harness, agent, session.id.value).tools.mapNotNull { it.identity?.localId }.toSet()
            assertTrue("memory" in offered && "knowledge" in offered && "tasks" in offered)
            assertTrue(offered.none { it in setOf("activate_skill", "artifact", "schedule") })
            harness.composer.runner(agent, session.id).run(session.id, "hi").toList()
            assertTrue("memory" in seen.last())
            assertFalse("schedule" in seen.last())
        } finally { harness.close() }
    }

    @Test fun runsInheritLatestAgentEffortAndRetainExplicitSessionEffort() = runBlocking {
        val efforts = mutableListOf<String>()
        val configured = object : TestModelProvider by provider {
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
                harness.composer.runner(updated, session.id).run(session.id, "hi").toList()
            }
            assertEquals(listOf("high", "high", "low"), efforts)
        } finally { harness.close() }
    }

}
