package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.either
import kinuvo.harness.Agent
import kinuvo.harness.ExecutionBroker
import kinuvo.harness.ExecutionAcceptance
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ExecutionSession
import kinuvo.harness.ExecutionFactory
import kinuvo.harness.ExecutionState
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.server.plugin.ServerPlugin
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.ToolRun
import kinuvo.server.plugin.toolSpec
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.server.tools.AgentManageScope
import kinuvo.server.tools.AgentManageTools
import kotlinx.serialization.json.jsonObject
import kinuvo.protocol.agent.AgentId
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.Frame
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.AgentRegistration
import kinuvo.protocol.execution.ExecutionCapabilities
import kinuvo.protocol.execution.ExecutionConnection
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.execution.ExposureGrant
import kinuvo.protocol.execution.HarnessConnection
import kinuvo.protocol.execution.SaveRemoteLink
import kinuvo.protocol.session.*
import kinuvo.protocol.workflow.*
import kinuvo.server.execution.RemoteLinks
import kinuvo.server.execution.executionSnapshot
import kinuvo.harness.ExecutionRejected
import kinuvo.server.rpc.serverRoutes
import kinuvo.testing.harness.ModelScript
import kinuvo.testing.server.TestServer
import kinuvo.testing.server.FakeHarness
import kinuvo.testing.server.FakeTool
import kinuvo.testing.server.FakeTools
import kinuvo.testing.server.FakeModels
import kinuvo.testing.server.FakeModelProvider
import kinuvo.protocol.model.ModelInfo
import kinuvo.protocol.model.ModelTier
import kinuvo.protocol.capability.ExecutionLocation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.*
import kotlinx.io.files.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SubAgentsTest {
    private suspend fun <T> value(block: suspend context(Raise<SubAgentError>) () -> T): T = either { block() }.getOrNull() ?: error("Sub-agent operation failed")

    private suspend fun reports(server: TestServer, agent: Agent, parent: SessionInfo) =
        server.harness.store.entries(agent.id, parent.id).filterIsInstance<SessionEntry.ToolReport>().filter { it.children.isNotEmpty() }

    private suspend fun untilReady(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test fun externalChildWakesAnIdleParentAndItsLargeResultRemainsReadable() = runBlocking {
        val large = "é".repeat(9_000)
        val external = FakeHarness { text -> reply(if (text == "follow up") "new answer" else large) }
        lateinit var agent: Agent
        TestServer.start(harnesses = listOf(external), script = { reply("parent awake") }, scenario = {
            agent = agent("work") { it.copy(approvals = "full") }
            externalAgent(external, "external", "External")
        }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("external"), AgentId("external"), "work") }
            untilReady { server.harness.store.entries(agent.id, parent.id).any { it is SessionEntry.AssistantMessage } }
            val report = reports(server, agent, parent).single()
            assertEquals(child.sessionId, report.children.single().sessionId)
            assertEquals(SubAgentStatus.Completed, report.children.single().status)
            assertContains(report.content, "Use agents_wait")
            assertFalse(report.content.contains(large))
            assertContains(report.content, "turn_id \"${child.turnId}\"")
            assertTrue(server.harness.store.entries(agent.id, parent.id).none { it is SessionEntry.UserMessage })
            repeat(2) {
                assertEquals(large, value { children.wait(agent.id, parent.id, listOf(child.sessionId), 0) }.single().result)
            }
            assertEquals(1, reports(server, agent, parent).size)
            value { children.send(agent.id, parent.id, child.sessionId, ToolCallId("follow"), "follow up") }
            assertEquals("new answer", value { children.wait(agent.id, parent.id, listOf(child.sessionId), 5) }.single().result)
            val original = children.tools(agent.id, parent.id).single { it.descriptor.name == "agents_wait" }.execute(buildJsonObject {
                putJsonArray("session_ids") { add(child.sessionId.value) }
                put("turn_id", child.turnId)
            }, "exact")
            assertEquals(large, ProtocolJson.parseToJsonElement(original).jsonObject.getValue("children").jsonArray
                .single().jsonObject.getValue("result").jsonPrimitive.content)
            server.harness.log.deleteSession(agent.id, parent.id)
            assertTrue(server.harness.store.sessions(child.agentId).none { it.id == child.sessionId })
        }
    }

    @Test fun completionsBatchAtTheParentsNextSafePointAndPrivateResultsStayHidden() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("hold") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}"""); reply("parent done") }
            on("regular") { reply("regular answer") }
            on("hidden") { reply("secret child answer") }
        }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id, "hold")
            untilReady { server.harness.log.pending(parent.id).isNotEmpty() }
            val children = server.harness.subAgents!!
            val regular = value { children.spawn(agent.id, parent.id, ToolCallId("regular"), agent.id, "regular") }
            val hidden = value { children.spawn(agent.id, parent.id, ToolCallId("hidden"), agent.id, "hidden", private = true) }
            untilReady { children.list(agent.id, parent.id).values.count { it.status == SubAgentStatus.Completed } == 2 }
            assertTrue(reports(server, agent, parent).isEmpty())
            assertNull(children.list(agent.id, parent.id).getValue(hidden.sessionId).result)
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val question = server.harness.log.pending(parent.id).single()
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, question.id, InteractionAnswer.Questions(mapOf("ok" to listOf("yes"))))))
            untilReady { reports(server, agent, parent).isNotEmpty() }
            val report = reports(server, agent, parent).single()
            assertEquals(setOf(regular.sessionId, hidden.sessionId), report.children.map { it.sessionId }.toSet())
            assertContains(report.content, "regular answer")
            assertContains(report.content, "awaiting your approval")
            val entries = server.harness.store.entries(agent.id, parent.id)
            assertFalse(entries.any { ProtocolJson.encodeToString(SessionEntry.serializer(), it).contains("secret child answer") })
            assertEquals(listOf("hold"), entries.filterIsInstance<SessionEntry.UserMessage>().map { it.text })
        }
    }

    @Test fun waitDeliversOnceAndCancellationNeverWakesTheParent() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("hold") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}"""); reply("child done") }
        }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val tools = children.tools(agent.id, parent.id).associateBy { it.descriptor.name }
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("first"), agent.id, "hold") }
            untilReady { server.harness.log.pending(parent.id).isNotEmpty() }
            val ids = buildJsonObject { putJsonArray("session_ids") { add(child.sessionId.value) } }
            val timeout = tools.getValue("agents_wait").execute(JsonObject(ids + ("timeout_seconds" to JsonPrimitive(0))), "timeout")
            val timedOut = ProtocolJson.parseToJsonElement(timeout).jsonObject
            assertTrue(timedOut.getValue("timed_out").jsonPrimitive.boolean)
            assertContains(timedOut.getValue("next").jsonPrimitive.content, "Do not wait")
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { tools.getValue("agents_wait").execute(ids, "wait") }
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val question = server.harness.log.pending(parent.id).single()
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, question.id, InteractionAnswer.Questions(mapOf("ok" to listOf("yes"))))))
            val result = ProtocolJson.parseToJsonElement(waiting.await()).jsonObject
            assertFalse(result.getValue("timed_out").jsonPrimitive.boolean)
            assertNull(result["next"])
            assertEquals("child done", result.getValue("children").jsonArray.single().jsonObject.getValue("result").jsonPrimitive.content)
            assertEquals(result, ProtocolJson.parseToJsonElement(tools.getValue("agents_wait").execute(ids, "repeat")))
            val cancelled = value { children.spawn(agent.id, parent.id, ToolCallId("second"), agent.id, "hold") }
            untilReady { server.harness.log.pending(parent.id).isNotEmpty() }
            tools.getValue("agents_cancel").execute(buildJsonObject { put("session_id", cancelled.sessionId.value); put("reason", "obsolete") }, "cancel")
            untilReady { children.list(agent.id, parent.id).getValue(cancelled.sessionId).status == SubAgentStatus.Cancelled }
            delay(100)
            assertTrue(reports(server, agent, parent).isEmpty())
        }
    }

    @Test fun stopKeepsPendingWakesAndQueuedMessagesPausedUntilUserSend() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("hold") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}""") }
            on("child") { reply("child answer") }
        }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id, "hold")
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            untilReady { server.harness.log.pending(parent.id).isNotEmpty() }
            client.call(Api.Send, SendParams(agent.id, parent.id, "queued", delivery = SendDelivery.Queue))
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("child"), agent.id, "child") }
            untilReady { children.list(agent.id, parent.id)[child.sessionId]?.status == SubAgentStatus.Completed }
            client.call(Api.Stop, StopParams(parent.id))
            untilReady { server.harness.log.pending(parent.id).isEmpty() }
            delay(100)
            assertTrue(reports(server, agent, parent).isEmpty())
            assertEquals(listOf("hold"), server.harness.store.entries(agent.id, parent.id)
                .filterIsInstance<SessionEntry.UserMessage>().map { it.text })
            client.call(Api.Send, SendParams(agent.id, parent.id, "resume"))
            untilReady { reports(server, agent, parent).isNotEmpty() }
            assertEquals(child.sessionId, reports(server, agent, parent).single().children.single().sessionId)
            untilReady { server.harness.store.entries(agent.id, parent.id).filterIsInstance<SessionEntry.UserMessage>().size == 3 }
        }
    }

    @Test fun cancellingAWorkflowStepWithoutAnActiveTurnCancelsItsChildren() = runBlocking {
        val agent = AgentId("work")
        val turn = StepId("turn")
        val approval = StepId("approval")
        TestServer.start(script = {
            on("hold child") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}""") }
        }, scenario = { agent("work") { it.copy(approvals = "full") } }).use { server ->
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val workflow = client.call(Api.CreateWorkflow, Workflow(WorkflowId("w"), "Workflow",
                listOf(WorkflowStep.AgentTurn(turn, agent, "first"), WorkflowStep.Approval(approval, "Continue?")),
                listOf(WorkflowEdge(turn, approval)), agentId = agent))
            val run = client.call(Api.RunWorkflow, RunWorkflowParams(workflow.id))
            var session: SessionId? = null
            untilReady {
                val step = client.call(Api.WorkflowRuns, workflow.id).single().steps.getValue(approval)
                session = step.sessionId
                step.status == StepStatus.WaitingApproval
            }
            val id = assertNotNull(session)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent, id, ToolCallId("child"), agent, "hold child") }
            untilReady { server.harness.log.pending(child.sessionId).isNotEmpty() }

            assertEquals(WorkflowRunStatus.Cancelled, client.call(Api.CancelWorkflow, run.id).status)
            untilReady { children.list(agent, id).getValue(child.sessionId).status == SubAgentStatus.Cancelled }
            assertTrue(server.harness.store.entries(agent, id).filterIsInstance<SessionEntry.ToolReport>().none { it.children.isNotEmpty() })
        }
    }

    @Test fun deletingAnIdleParentDropsAPendingWake() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("child"), agent.id, "work") }
            untilReady { children.list(agent.id, parent.id)[child.sessionId]?.status == SubAgentStatus.Completed }
            server.harness.log.deleteSession(agent.id, parent.id)
            delay(100)
            assertTrue(server.harness.store.sessions(agent.id).none { it.id == parent.id || it.id == child.sessionId })
        }
    }

    @Test fun grandchildWakeFinishesANewChildTurnWithoutResettingTheRootBudget(): Unit = runBlocking {
        val releaseChild = CompletableDeferred<Unit>()
        val releaseGrandchild = CompletableDeferred<Unit>()
        val external = FakeHarness { text ->
            when (text) {
                "child work" -> { releaseChild.await(); reply("child first answer") }
                "grandchild work" -> { releaseGrandchild.await(); reply("grandchild answer") }
                else -> if ("grandchild answer" in text) reply("wake answer")
            }
        }
        lateinit var agent: Agent
        TestServer.start(harnesses = listOf(external), scenario = {
            agent = agent("work") { it.copy(approvals = "full") }
            externalAgent(external, "external", "External")
        }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            children.consume(parent.id)
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("child"), AgentId("external"), "child work") }
            value { children.spawn(child.agentId, child.sessionId, ToolCallId("grandchild"), child.agentId, "grandchild work") }
            releaseChild.complete(Unit)
            untilReady { reports(server, agent, parent).any { "child first answer" in it.content } }
            releaseGrandchild.complete(Unit)
            untilReady { children.list(agent.id, parent.id)[child.sessionId]?.let {
                it.turnId != child.turnId && it.status == SubAgentStatus.Completed
            } == true }
            val finished = children.list(agent.id, parent.id).getValue(child.sessionId)
            assertEquals("wake answer", finished.result)
            untilReady { reports(server, agent, parent).any { it.children.any { completion -> completion.turnId == finished.turnId } } }
            repeat(63) { children.consume(parent.id) }
            assertFailsWith<CancellationException> { children.consume(parent.id) }
        }
    }

    @Test fun onlyUserRoutineAndWorkflowTurnsResetTheRootBudget() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val runs = Runs(server.harness.log, this, server.harness.native).also { it.onBegin = children::begin }
            val background = listOf(Runs.Kind.Report, Runs.Kind.Task)
            repeat(32) { children.consume(parent.id) }
            for (kind in background) runs.start(agent, parent.id, ExecutionInput("background turn"), kind = kind).job.join()
            repeat(32) { children.consume(parent.id) }
            assertFailsWith<CancellationException> { children.consume(parent.id) }
            runs.start(agent, parent.id, ExecutionInput("background report"), kind = Runs.Kind.Report).job.join()
            assertFailsWith<CancellationException> { children.consume(parent.id) }
            assertFailsWith<CancellationException> { runs.start(agent, parent.id, ExecutionInput("background turn"), kind = Runs.Kind.Task) }
            for (kind in listOf(Runs.Kind.Routine, Runs.Kind.Workflow, Runs.Kind.User)) {
                runs.start(agent, parent.id, ExecutionInput("new turn"), kind = kind).job.join()
                repeat(63) { children.consume(parent.id) }
            }
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("child"), agent.id, "work") }
            assertEquals(SubAgentStatus.Completed, value { children.wait(agent.id, parent.id, listOf(child.sessionId), 5) }.single().status)
        }
    }

    @Test fun directChildTurnCanSpawnAndWakeAfterStoppingTheRoot() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("hold") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}""") }
            on("restart") { reply("restarted") }
            on("grandchild") { reply("grandchild answer") }
        }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("child"), agent.id, "hold") }
            untilReady { server.harness.log.pending(parent.id).isNotEmpty() }
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            client.call(Api.Stop, StopParams(parent.id))
            untilReady { children.list(agent.id, parent.id).getValue(child.sessionId).status == SubAgentStatus.Cancelled }

            client.call(Api.Send, SendParams(agent.id, child.sessionId, "restart"))
            untilReady { children.list(agent.id, parent.id).getValue(child.sessionId).result == "restarted" }
            value { children.spawn(agent.id, child.sessionId, ToolCallId("grandchild"), agent.id, "grandchild") }
            val childInfo = server.harness.store.session(agent.id, child.sessionId)
            untilReady { reports(server, agent, childInfo).any { "grandchild answer" in it.content } }
            assertEquals(SubAgentStatus.Completed, children.list(agent.id, child.sessionId).values.single().status)
            assertTrue(reports(server, agent, parent).isEmpty())
        }
    }

    @Test fun backgroundToolReportsAfterRootStop() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tool = object : Tool {
            override val descriptor = toolSpec("background_work", "Test background work")
            override suspend fun execute(args: JsonObject): String = error("Expected a tool run")
            override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String =
                run.background("Started") { started.complete(Unit); release.await(); "Background work finished" }
        }
        val plugin = object : ServerPlugin {
            override val definition = PluginDefinition("test.background", "Background")
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(tool))
        }
        lateinit var agent: Agent
        TestServer.start(plugins = listOf(plugin), script = {
            on("start work") { toolCall("background_work"); reply("Work started") }
        }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id, "start work")
            withTimeout(5_000) { started.await() }
            val client = server.client().also { it.connect() }
            client.call(Api.Stop, StopParams(parent.id))
            release.complete(Unit)
            untilReady {
                server.harness.store.entries(agent.id, parent.id).filterIsInstance<SessionEntry.ToolReport>()
                    .any { it.tool == "background_work" && it.content == "Background work finished" }
            }
        }
    }

    @Test fun nativeWakeRetriesFailedModelSetupWithOneSavedReport() = runBlocking {
        val fixture = RuntimeFixture()
        val model = ModelScript().apply { reply("reply") }.model
        val attempts = AtomicInteger()
        val harness = testRuntime(fixture.dependencies, modelFactory = {
            if (attempts.incrementAndGet() == 2) error("Temporary model setup failure")
            model
        })
        val agent = fixture.agent
        val parent = harness.newSession(agent)
        val directory = Files.createTempDirectory("native-wake-model").toFile()
        val runtime = ServerRuntime(harness, DeviceAuth(Path(directory.path, "devices.json")), serverRoutes())
        try {
            runtime.start()
            val child = value { harness.subAgents!!.spawn(agent.id, parent.id, ToolCallId("child"), agent.id, "work") }
            untilReady { harness.store.entries(agent.id, parent.id).any { it is SessionEntry.AssistantMessage } }
            val report = harness.store.entries(agent.id, parent.id).filterIsInstance<SessionEntry.ToolReport>().single()
            assertEquals(child.sessionId, report.children.single().sessionId)
            assertEquals(3, attempts.get())
        } finally {
            runtime.close()
            directory.deleteRecursively()
        }
    }

    @Test fun rootNativeWakePublishesErrorOnlyAfterBothModelSetupsFail() = runBlocking {
        val fixture = RuntimeFixture()
        val model = ModelScript().model
        val attempts = AtomicInteger()
        val harness = testRuntime(fixture.dependencies, modelFactory = {
            if (attempts.incrementAndGet() > 1) error("Wake model setup failed")
            model
        })
        val agent = fixture.agent
        val parent = harness.newSession(agent)
        val errors = AtomicInteger()
        harness.log.onEvent { _, id, event -> if (id == parent.id && event is AgentEvent.RunError) errors.incrementAndGet() }
        val directory = Files.createTempDirectory("native-wake-exhausted").toFile()
        val runtime = ServerRuntime(harness, DeviceAuth(Path(directory.path, "devices.json")), serverRoutes())
        try {
            runtime.start()
            value { harness.subAgents!!.spawn(agent.id, parent.id, ToolCallId("child"), agent.id, "work") }
            untilReady { errors.get() > 0 }
            assertEquals(1, errors.get())
            assertEquals(3, attempts.get())
            assertEquals("Wake model setup failed", assertIs<AgentEvent.RunError>(harness.log.completion(parent.id)).message)
        } finally {
            runtime.close()
            directory.deleteRecursively()
        }
    }

    @Test fun nativeWakeRetriesFailedFactoryOpenWithOneSavedReport() = runBlocking {
        val fixture = RuntimeFixture()
        val harness = fixture.runtime(ModelScript().apply { reply("reply") }.model)
        val agent = fixture.agent
        val parent = harness.newSession(agent)
        var attempts = 0
        val factory = object : ExecutionFactory by harness.native {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                if (state.sessionId == parent.id && ++attempts == 1) error("Temporary factory startup failure")
                return harness.native.open(config, registration, state, broker, scope)
            }
        }
        val directory = Files.createTempDirectory("native-wake").toFile()
        val runtime = ServerRuntime(harness, DeviceAuth(Path(directory.path, "devices.json")), serverRoutes(), nativeFactory = factory)
        try {
            runtime.start()
            val children = harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("child"), agent.id, "work") }
            untilReady { harness.store.entries(agent.id, parent.id).any { it is SessionEntry.AssistantMessage } }
            val report = harness.store.entries(agent.id, parent.id).filterIsInstance<SessionEntry.ToolReport>().single()
            assertEquals(child.sessionId, report.children.single().sessionId)
            assertEquals(2, attempts)
        } finally {
            runtime.close()
            directory.deleteRecursively()
        }
    }

    @Test fun nativeChildSettlesAfterBothWakeStartupsFail() = runBlocking {
        val fixture = RuntimeFixture()
        val harness = fixture.runtime(ModelScript().apply { reply("reply") }.model)
        val agent = fixture.agent
        val parent = harness.newSession(agent)
        val fail = CompletableDeferred<SessionId>()
        var attempts = 0
        val factory = object : ExecutionFactory by harness.native {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                if (fail.isCompleted && state.sessionId == fail.await()) {
                    attempts++
                    error("Wake startup failed")
                }
                return harness.native.open(config, registration, state, broker, scope)
            }
        }
        val directory = Files.createTempDirectory("native-wake").toFile()
        val runtime = ServerRuntime(harness, DeviceAuth(Path(directory.path, "devices.json")), serverRoutes(), nativeFactory = factory)
        try {
            runtime.start()
            val children = harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("child"), agent.id, "work") }
            untilReady { children.list(agent.id, parent.id)[child.sessionId]?.status == SubAgentStatus.Completed }
            fail.complete(child.sessionId)
            value { children.spawn(agent.id, child.sessionId, ToolCallId("grandchild"), agent.id, "grandchild") }
            untilReady { children.list(agent.id, parent.id)[child.sessionId]?.status == SubAgentStatus.Failed }
            assertEquals(2, attempts)
            assertEquals("Wake startup failed", value { children.wait(agent.id, parent.id, listOf(child.sessionId), 1) }.single().result)
        } finally {
            runtime.close()
            directory.deleteRecursively()
        }
    }

    @Test fun failedSpawnSavesItsStatusWithoutWakingTheParent() = runBlocking {
        lateinit var agent: Agent
        val external = FakeHarness()
        val busyHarness = object : ExecutionFactory by external {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) =
                external.open(config, registration, state, broker, scope).also { state.update(ExecutionPhase.Running) }
        }
        TestServer.start(executionFactory = { busyHarness }, scenario = {
            agent = agent("work") { it.copy(approvals = "full") }
            externalAgent(external, "external")
        }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            assertFailsWith<IllegalStateException> {
                value { children.spawn(agent.id, parent.id, ToolCallId("bad"), AgentId("external"), "work") }
            }
            assertEquals(SubAgentStatus.Failed, children.list(agent.id, parent.id).values.single().status)
            delay(100)
            assertTrue(reports(server, agent, parent).isEmpty())
        }
    }

    @Test fun remoteChildSettlesWhenSourceCancelsBeforeWritingEntries(): Unit = runBlocking {
        val fixture = RuntimeFixture()
        val source = fixture.runtime(ModelScript().model)
        val directory = Files.createTempDirectory("subagent-remote-source").toFile()
        val auth = DeviceAuth(Path(directory.path, "devices.json"))
        val links = RemoteLinks()
        val token = links.grant(ExposureGrant("test", setOf(fixture.agent.id)))
        val sourceSending = CompletableDeferred<Unit>()
        val sourceSession = CompletableDeferred<SessionId>()
        val holdSnapshot = CompletableDeferred<Unit>()
        val snapshotHeld = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val routeTable = serverRoutes()
        val cancelOnSend = object : Routes by routeTable {
            override suspend fun dispatch(call: Call, request: Frame.Request): Frame.Response {
                if (request.method != Api.RemoteExecution.name) return routeTable.dispatch(call, request)
                val input = ProtocolJson.decodeFromJsonElement(Api.RemoteExecution.params, request.params)
                if (input.action == "send") sourceSending.complete(Unit)
                if (input.action == "snapshot" && sourceSending.isCompleted) sourceSession.await()
                if (input.action == "snapshot" && holdSnapshot.isCompleted) {
                    snapshotHeld.complete(Unit)
                    releaseSnapshot.await()
                }
                val response = routeTable.dispatch(call, request)
                if (response.ok && input.action == "send") {
                    val id = requireNotNull(input.sessionId)
                    call.server.dispatch.stop(id)
                    call.server.dispatch.join(id)
                    sourceSession.complete(id)
                }
                return response
            }
        }
        val factory = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) =
                object : ExecutionSession {
                    override val capabilities = ExecutionCapabilities()
                    override suspend fun prompt(input: ExecutionInput): Nothing = awaitCancellation()
                    override suspend fun cancel() {}
                    override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {}
                }
        }
        val runtime = ServerRuntime(source, auth, cancelOnSend, nativeFactory = factory, remoteLinks = links)
        val listener = NetworkListener(runtime, auth)
        try {
            runtime.start(schedule = false)
            val port = listener.start("127.0.0.1", emptyList())
            lateinit var agent: Agent
            TestServer.start(scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
                val client = server.client(); client.connect()
                client.call(Api.SaveRemoteLink, SaveRemoteLink("source", "ws://127.0.0.1:$port/ws", token))
                client.call(Api.SaveHarnessConnection, HarnessConnection("remote", "Remote", ExecutionConnection.Remote("source")))
                val alias = AgentId("alias")
                client.call(Api.RegisterExternalAgent, AgentRegistration(alias, "Alias", "remote", remoteAgentId = fixture.agent.id.value))
                val parent = server.startSession(agent.id)
                val children = server.harness.subAgents!!
                val child = value { children.spawn(agent.id, parent.id, ToolCallId("remote"), alias, "work") }
                val id = withTimeout(5_000) { sourceSession.await() }
                assertTrue(source.store.entries(fixture.agent.id, id).all { it is SessionEntry.Started || it is SessionEntry.Details })
                untilReady { children.list(agent.id, parent.id).getValue(child.sessionId).status != SubAgentStatus.Running }
                val result = children.list(agent.id, parent.id).getValue(child.sessionId)
                assertEquals(SubAgentStatus.Cancelled, result.status)
                assertNull(result.result)
                assertTrue(reports(server, agent, parent).isEmpty())
                holdSnapshot.complete(Unit)
                withTimeout(5_000) { snapshotHeld.await() }
                try {
                    withTimeout(1_000) { client.call(Api.Stop, StopParams(child.sessionId)) }
                } finally {
                    releaseSnapshot.complete(Unit)
                }
            }
        } finally {
            releaseSnapshot.complete(Unit)
            listener.stop()
            runtime.close()
            directory.deleteRecursively()
        }
    }

    @Test fun failedExternalWakeIsAutomaticallyRescheduled() = runBlocking {
        lateinit var agent: Agent
        lateinit var parentState: ExecutionState
        val external = FakeHarness()
        val busyHarness = object : ExecutionFactory by external {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) =
                external.open(config, registration, state, broker, scope).also {
                    parentState = state
                    state.update(ExecutionPhase.Running)
                }
        }
        TestServer.start(executionFactory = { busyHarness }, scenario = {
            agent = agent("work") { it.copy(approvals = "full") }
            externalAgent(external, "external")
        }).use { server ->
            val parent = server.startSession(AgentId("external"))
            val externalSession = external.sessions.single()
            val children = server.harness.subAgents!!
            val child = value { children.spawn(parent.agentId, parent.id, ToolCallId("child"), agent.id, "work") }
            untilReady { children.list(parent.agentId, parent.id)[child.sessionId]?.status == SubAgentStatus.Completed }
            delay(100)
            assertTrue(externalSession.prompts.isEmpty())
            parentState.update(ExecutionPhase.Idle)
            untilReady { externalSession.prompts.isNotEmpty() }
            assertContains(externalSession.prompts.single().text, child.sessionId.value)
            untilReady { parentState.snapshot().phase == ExecutionPhase.Idle }
        }
    }

    @Test fun asyncExternalRejectionRetriesTheReportAndDrainsNewerCompletions() = rejectedWakeDrainsNewerCompletions(1)

    @Test fun exhaustedWakeWaitsForLaterTurnWhileNewerCompletionsDrain() = rejectedWakeDrainsNewerCompletions(2)

    private fun rejectedWakeDrainsNewerCompletions(rejections: Int) = runBlocking {
        lateinit var agent: Agent
        lateinit var parentState: ExecutionState
        val prompted = CompletableDeferred<Unit>()
        val reject = CompletableDeferred<Unit>()
        val external = FakeHarness()
        val attempts = AtomicInteger()
        val rejectsWake = object : ExecutionFactory by external {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                parentState = state
                val delegate = external.open(config, registration, state, broker, scope)
                return object : ExecutionSession by delegate {
                    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
                        if (attempts.incrementAndGet() <= rejections) {
                            prompted.complete(Unit)
                            reject.await()
                            throw ExecutionRejected("Confirmed rejection")
                        }
                        return delegate.prompt(input)
                    }
                }
            }
        }
        TestServer.start(executionFactory = { rejectsWake }, scenario = {
            agent = agent("work") { it.copy(approvals = "full") }
            externalAgent(external, "external")
        }).use { server ->
            val parent = server.startSession(AgentId("external"))
            val children = server.harness.subAgents!!
            val child = value { children.spawn(parent.agentId, parent.id, ToolCallId("child"), agent.id, "work") }
            withTimeout(5_000) { prompted.await() }
            val newer = value { children.spawn(parent.agentId, parent.id, ToolCallId("newer"), agent.id, "new work") }
            untilReady { children.list(parent.agentId, parent.id)[newer.sessionId]?.status == SubAgentStatus.Completed }
            reject.complete(Unit)
            untilReady {
                external.sessions.single().prompts.size == 3 - rejections && parentState.snapshot().phase == ExecutionPhase.Idle
            }
            val prompts = external.sessions.single().prompts
            assertContains(prompts.last().text, newer.sessionId.value)
            assertFalse(prompts.last().text.contains(child.sessionId.value))
            assertEquals(3, attempts.get())
            if (rejections == 2) {
                val client = server.client().also { it.connect() }
                client.call(Api.Send, SendParams(parent.agentId, parent.id, "continue"))
                untilReady {
                    external.sessions.single().prompts.any { child.sessionId.value in it.text } &&
                        parentState.snapshot().phase == ExecutionPhase.Idle
                }
            }
            val saved = server.harness.store.entries(parent.agentId, parent.id).filterIsInstance<SessionEntry.ToolReport>()
            assertEquals(listOf(child.sessionId, newer.sessionId), saved.map { it.children.single().sessionId })
        }
    }

    @Test fun waitRetiresAQueuedWakeRetry() = runBlocking {
        lateinit var agent: Agent
        lateinit var parentState: ExecutionState
        val rejected = AtomicInteger()
        val external = FakeHarness()
        val rejectsFirstWake = object : ExecutionFactory by external {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                parentState = state
                val delegate = external.open(config, registration, state, broker, scope)
                return object : ExecutionSession by delegate {
                    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
                        if (rejected.compareAndSet(0, 1)) throw ExecutionRejected("Retry this wake")
                        return delegate.prompt(input)
                    }
                }
            }
        }
        TestServer.start(executionFactory = { rejectsFirstWake }, scenario = {
            agent = agent("work") { it.copy(approvals = "full") }
            externalAgent(external, "external")
        }).use { server ->
            val parent = server.startSession(AgentId("external"))
            val children = server.harness.subAgents!!
            val child = value { children.spawn(parent.agentId, parent.id, ToolCallId("child"), agent.id, "work") }
            untilReady { rejected.get() == 1 && parentState.snapshot().phase == ExecutionPhase.Idle }
            val waited = value { children.wait(parent.agentId, parent.id, listOf(child.sessionId), 1) }.single()
            assertEquals(SubAgentStatus.Completed, waited.status)
            val newer = value { children.spawn(parent.agentId, parent.id, ToolCallId("newer"), agent.id, "new work") }
            untilReady { external.sessions.single().prompts.any { newer.sessionId.value in it.text } }
            assertTrue(external.sessions.single().prompts.none { child.sessionId.value in it.text })
        }
    }

    @Test fun externalChildSettlesAfterBothWakePromptsAreRejected() = runBlocking {
        lateinit var agent: Agent
        val external = FakeHarness()
        val rejectsWake = object : ExecutionFactory by external {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                val delegate = external.open(config, registration, state, broker, scope)
                return object : ExecutionSession by delegate {
                    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance {
                        if (input.text.startsWith("Sub-agent ")) throw ExecutionRejected("Wake rejected")
                        return delegate.prompt(input)
                    }
                }
            }
        }
        TestServer.start(executionFactory = { rejectsWake }, scenario = {
            agent = agent("work") { it.copy(approvals = "full") }
            externalAgent(external, "external")
        }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("child"), AgentId("external"), "work") }
            untilReady { children.list(agent.id, parent.id)[child.sessionId]?.status == SubAgentStatus.Completed }
            value { children.spawn(child.agentId, child.sessionId, ToolCallId("grandchild"), agent.id, "grandchild") }
            untilReady { children.list(agent.id, parent.id)[child.sessionId]?.status == SubAgentStatus.Failed }
            val failed = value { children.wait(agent.id, parent.id, listOf(child.sessionId), 1) }.single()
            assertEquals(SubAgentStatus.Failed, failed.status)
            assertEquals("Wake rejected", failed.result)
        }
    }

    @Test fun spawnLosingRegistrationToParentDeletionRemovesExternalChild() = runBlocking {
        lateinit var agent: Agent
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val external = FakeHarness()
        val slow = object : ExecutionFactory by external {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
                opening.complete(Unit)
                release.await()
                return external.open(config, registration, state, broker, scope)
            }
        }
        TestServer.start(executionFactory = { slow }, scenario = {
            agent = agent("work")
            externalAgent(external, "external")
        }).use { server ->
            val parent = server.startSession(agent.id)
            val alias = AgentId("external")
            val children = server.harness.subAgents!!
            val spawned = async { runCatching { value { children.spawn(agent.id, parent.id, ToolCallId("child"), alias, "work") } } }
            try {
                withTimeout(5_000) { opening.await() }
                withTimeout(5_000) { server.harness.log.deleteSession(agent.id, parent.id) }
            } finally {
                release.complete(Unit)
            }
            assertIs<CancellationException>(withTimeout(5_000) { spawned.await() }.exceptionOrNull())
            assertTrue(server.harness.store.sessions(alias).isEmpty())
            assertTrue(server.harness.store.sessions(agent.id).none { it.id == parent.id })
        }
    }

    @Test fun followUpCanSteerAChildAtItsNextModelRequest() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("hold") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}"""); reply("original answer") }
            on("adjust") { reply("steered answer") }
        }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("spawn"), agent.id, "hold") }
            untilReady { server.harness.log.pending(parent.id).isNotEmpty() }
            val tool = children.tools(agent.id, parent.id).single { it.descriptor.name == "agents_send" }
            val sent = tool.execute(buildJsonObject {
                put("session_id", child.sessionId.value); put("text", "adjust"); put("mode", "steer")
            }, "send")
            val result = ProtocolJson.parseToJsonElement(sent).jsonObject
            assertEquals("steer", result.getValue("mode").jsonPrimitive.content)
            assertContains(result.getValue("next").jsonPrimitive.content, "Completion wakes this Session")
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val question = server.harness.log.pending(parent.id).single()
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, question.id, InteractionAnswer.Questions(mapOf("ok" to listOf("yes"))))))
            val finished = value { children.wait(agent.id, parent.id, listOf(child.sessionId), 5) }.single()
            assertEquals(child.callId, finished.callId)
            assertEquals("steered answer", finished.result)
        }
    }

    @Test fun targetsResolveByIdOrUnambiguousNameAndUnknownLeavesNoChild() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(scenario = { agent = agent("dev", "Developer") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val error = either { children.spawn(agent.id, parent.id, ToolCallId("bad"), AgentId("missing"), "task") }.leftOrNull()
            assertIs<SubAgentError.Target>(error)
            assertTrue(error.message.contains("dev (Developer)"))
            assertTrue(children.list(agent.id, parent.id).isEmpty())
            assertEquals(listOf(parent.id), server.harness.store.sessions(agent.id).map { it.id })
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("name"), AgentId("dEVELOPER"), "task") }
            assertEquals(agent.id, child.agentId)
            value { children.wait(agent.id, parent.id, listOf(child.sessionId), 5) }
            val catalog = children.tools(agent.id, parent.id).single { it.descriptor.name == "agents_list" }
                .execute(buildJsonObject {}, "list")
            val entry = ProtocolJson.parseToJsonElement(catalog).jsonObject.getValue("agents").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "dev" }.jsonObject
            assertEquals("dev", entry.getValue("id").jsonPrimitive.content)
            assertEquals("Developer", entry.getValue("name").jsonPrimitive.content)
            assertTrue(entry.getValue("on_device").jsonPrimitive.boolean)
        }
    }

    @Test fun resultsAreDurableAndBatchIdentityDoesNotDependOnCompletionOrder() = runBlocking {
        val home = Files.createTempDirectory("subagents").toFile()
        lateinit var agent: Agent
        lateinit var parent: SessionInfo
        lateinit var first: SubAgentState
        lateinit var second: SubAgentState
        try {
            TestServer.start(home = Path(home.path), script = {
                on("slow") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Continue?","kind":"text"}]}"""); reply("first answer") }
                on("fast") { reply("second answer") }
                on("follow up") { reply("continued") }
            }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
                parent = server.startSession(agent.id)
                val children = server.harness.subAgents!!
                first = value { children.spawn(agent.id, parent.id, ToolCallId("batch"), agent.id, "slow") }
                second = value { children.spawn(agent.id, parent.id, ToolCallId("batch"), agent.id, "fast") }
                val fast = value { children.wait(agent.id, parent.id, listOf(second.sessionId), 5) }.single()
                assertEquals("second answer", fast.result)
                val interaction = withTimeout(5000) {
                    while (true) {
                        server.harness.log.pending(parent.id).firstOrNull()?.let { return@withTimeout it }
                        yield()
                    }
                    error("unreachable")
                }
                val client = server.client(token = server.auth.sessionToken()); client.connect()
                assertTrue(client.call(Api.Respond, RespondParams(parent.id, interaction.id, InteractionAnswer.Questions(mapOf("ok" to listOf("yes"))))))
                val results = value { children.wait(agent.id, parent.id, listOf(first.sessionId, second.sessionId), 5) }.associateBy { it.sessionId }
                assertEquals("first answer", results[first.sessionId]?.result)
                assertEquals("second answer", results[second.sessionId]?.result)
                assertTrue(server.harness.log.page(agent.id, null, 100).sessions.none { it.id == first.sessionId || it.id == second.sessionId })
                value { children.send(agent.id, parent.id, first.sessionId, ToolCallId("follow"), "follow up") }
                assertEquals("continued", value { children.wait(agent.id, parent.id, listOf(first.sessionId), 5) }.single().result)
            }
            TestServer.start(home = Path(home.path)).use { restarted ->
                val results = value { restarted.harness.subAgents!!.wait(agent.id, parent.id, listOf(first.sessionId, second.sessionId), 0) }.associateBy { it.sessionId }
                assertEquals("continued", results[first.sessionId]?.result)
                assertEquals("second answer", results[second.sessionId]?.result)
            }
        } finally { home.deleteRecursively() }
    }

    @Test fun childApprovalIsAnsweredOnParentAndRunningFollowUpQueues() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("approval") { toolCall("agents_spawn", """{"goal":"nested"}"""); reply("approved turn done") }
            on("follow") { reply("follow done") }
        }, scenario = { agent = agent("work") }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("spawn"), agent.id, "approval") }
            val pending = withTimeout(5000) {
                while (true) {
                    server.harness.log.pending(parent.id).filterIsInstance<PendingInteraction.Approval>().firstOrNull()?.let { return@withTimeout it }
                    yield()
                }
                error("unreachable")
            }
            value { children.send(agent.id, parent.id, child.sessionId, ToolCallId("send"), "follow") }
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, pending.id, InteractionAnswer.Approval(ApprovalDecision.Deny()))))
            withTimeout(5000) { while (children.list(agent.id, parent.id)[child.sessionId]?.result != "follow done") yield() }
            assertTrue(server.harness.log.pending(parent.id).isEmpty())
        }
    }

    @Test fun hiddenSessionsRequireLocalModelsAndPinTheirSettings() = runBlocking {
        lateinit var local: Agent
        lateinit var cloud: Agent
        val models = FakeModels(FakeModelProvider("test", "Models", listOf(
            ModelInfo("local", location = ExecutionLocation.ServerHost), ModelInfo("cloud", location = ExecutionLocation.External),
        )))
        TestServer.start(plugins = listOf(models, FakeTools(FakeTool("fixture_plugin"))), scenario = {
            local = agent("local") { it.copy(provider = "test", model = "local", approvals = "full") }
            cloud = agent("cloud") { it.copy(provider = "test", model = "cloud", approvals = "full") }
        }).use { server ->
            val parent = server.startSession(cloud.id)
            val children = server.harness.subAgents!!
            val regular = value { children.spawn(cloud.id, parent.id, ToolCallId("regular"), cloud.id, "task") }
            assertFalse(server.harness.store.session(cloud.id, regular.sessionId).hidden)
            assertFalse(regular.private)
            assertTrue(server.harness.composer.compose(cloud, regular.sessionId).tools.tools.any { it.name == "fixture_plugin" })
            children.tools(cloud.id, parent.id).single { it.descriptor.name == "agents_wait" }.execute(buildJsonObject {
                putJsonArray("session_ids") { add(regular.sessionId.value) }
            }, "regular-delivery")
            assertEquals(SubAgentStatus.Completed, value { children.wait(cloud.id, parent.id, listOf(regular.sessionId), 5) }.single().status)
            assertTrue(server.harness.log.pending(parent.id).isEmpty())
            assertTrue(server.harness.log.page(cloud.id, null, 100).sessions.none { it.id == regular.sessionId })
            val error = assertFailsWith<ServerException> { value { children.spawn(cloud.id, parent.id, ToolCallId("cloud"), cloud.id, "task", private = true) } }
            assertEquals(SubAgentError.Privacy(cloud.name), error.error)
            assertTrue(error.message.orEmpty().contains("has no on-device model"))
            assertTrue(children.list(cloud.id, parent.id).values.none { it.private })
            val child = value { children.spawn(cloud.id, parent.id, ToolCallId("local"), local.id, "task", private = true) }
            assertEquals(SubAgentStatus.Completed, value { children.wait(cloud.id, parent.id, listOf(child.sessionId), 5) }.single().status)
            assertFailsWith<IllegalArgumentException> { server.harness.configure(local, child.sessionId, SessionSettings(provider = "test", model = "cloud")) }
            val configure = AgentManageTools(server.harness.agentManagement!!).tools(AgentManageScope(local.id, child.sessionId), hidden = true).single()
            assertFailsWith<IllegalArgumentException> { configure.preview(ProtocolJson.parseToJsonElement("""{"provider":"test","model":"cloud"}""").jsonObject) }
            val composed = server.harness.composer.compose(local, child.sessionId)
            assertTrue(composed.tools.tools.none { it.name == "fixture_plugin" })
            assertTrue(composed.tools.tools.none { it.name in setOf("memory", "web_fetch", "generate_image", "run_code", "agent_get", "agent_update", "agent_create", "mcp_list", "mcp_add", "mcp_remove", "mcp_test", "mcp_login") })
            val completed = children.list(cloud.id, parent.id).getValue(child.sessionId)
            val notice = SessionEntry.Notice("legacy", "sub_agent", "", child = completed.copy(result = "legacy private result"))
            val json = ProtocolJson.encodeToJsonElement(SessionEntry.serializer(), notice).jsonObject
            val legacy = ProtocolJson.decodeFromJsonElement(SessionEntry.serializer(), JsonObject(json + ("child" to
                JsonObject(json.getValue("child").jsonObject - "private"))))
            assertTrue(assertIs<SessionEntry.Notice>(legacy).child!!.private)
            server.harness.log.append(cloud.id, parent.id, legacy)
            val catchUp = mutableListOf<SessionChange>()
            val subscriber = SessionLog.Subscriber { catchUp.add(it.change); true }
            server.harness.log.subscribe(cloud.id, parent.id, null, 100, subscriber)
            server.harness.log.unsubscribe(parent.id, subscriber)
            val replayed = catchUp.filterIsInstance<SessionChange.Entry>().map { it.entry }.filterIsInstance<SessionEntry.Notice>()
            assertNull(replayed.single { it.id == "legacy" }.child?.result)
            assertNull(catchUp.filterIsInstance<SessionChange.Live>().single().state.children.getValue(child.sessionId).result)
            val past = server.harness.log.history(cloud.id, parent.id, Int.MAX_VALUE, 100)
            assertNull(past.map { it.entry }.filterIsInstance<SessionEntry.Notice>().single { it.id == "legacy" }.child?.result)
            server.harness.log.deleteSession(local.id, child.sessionId)
            val waitTool = children.tools(cloud.id, parent.id).single { it.descriptor.name == "agents_wait" }
            server.auth.refreshCode(management = false)
            val viewer = server.client(token = server.pairDevice("viewer"))
            assertFalse(viewer.connect().management)
            val client = server.client(token = server.auth.sessionToken())
            assertTrue(client.connect().management)
            val catchUpParams = CatchUpParams(cloud.id, parent.id)
            viewer.call(Api.CatchUp, catchUpParams)
            val liveApproval = async(start = CoroutineStart.UNDISPATCHED) {
                viewer.events(Api.Update).mapNotNull {
                    ((it.change as? SessionChange.Event)?.event as? AgentEvent.InteractionRequested)
                        ?.interaction as? PendingInteraction.Approval
                }.first()
            }
            suspend fun catchUpApproval(viewer: KinuvoClient): PendingInteraction.Approval = coroutineScope {
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    viewer.events(Api.Update).mapNotNull {
                        (it.change as? SessionChange.Live)?.state?.pending?.filterIsInstance<PendingInteraction.Approval>()?.singleOrNull()
                    }.first()
                }
                viewer.call(Api.CatchUp, catchUpParams)
                withTimeout(5_000) { pending.await() }
            }
            val delivery = async { waitTool.execute(buildJsonObject {
                putJsonArray("session_ids") { add(child.sessionId.value) }
            }, "delivery") }
            val approval = withTimeout(5000) {
                while (true) {
                    server.harness.log.pending(parent.id).filterIsInstance<PendingInteraction.Approval>().firstOrNull()?.let { return@withTimeout it }
                    yield()
                }
                error("unreachable")
            }
            assertEquals("legacy private result", approval.preview?.body)
            assertNull(withTimeout(5_000) { liveApproval.await() }.preview?.body)
            assertNull(catchUpApproval(viewer).preview?.body)
            assertEquals("legacy private result", catchUpApproval(client).preview?.body)
            assertFalse(approval.args.contains("legacy private result"))
            val snapshot = server.harness.log.executionSnapshot(cloud.id, parent.id)
            assertFalse(ProtocolJson.encodeToString(snapshot).contains("legacy private result"))
            assertEquals(SubAgentError.Delivering, either {
                children.send(cloud.id, parent.id, child.sessionId, ToolCallId("pending"), "again")
            }.leftOrNull())
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, approval.id, InteractionAnswer.Approval(ApprovalDecision.Deny()))))
            assertTrue(delivery.await().contains("withheld"))
            assertTrue(waitTool.execute(buildJsonObject {
                putJsonArray("session_ids") { add(child.sessionId.value) }
            }, "repeat").contains("withheld"))
            assertTrue(server.harness.log.pending(parent.id).isEmpty())
            assertTrue(server.harness.store.sessions(local.id).none { it.id == child.sessionId })
            assertEquals(SubAgentError.Unknown(child.sessionId), either {
                children.send(cloud.id, parent.id, child.sessionId, ToolCallId("deleted"), "again")
            }.leftOrNull())
            server.harness.log.deleteSession(cloud.id, parent.id)
            assertTrue(server.harness.store.sessions(cloud.id).none { it.id == regular.sessionId })

        }
    }

    @Test fun deliveringAnOlderPrivateTurnTombstonesTheLatestCancelledTurn() = runBlocking {
        lateinit var agent: Agent
        val models = FakeModels(FakeModelProvider("test", "Models", listOf(
            ModelInfo("local", tools = true, location = ExecutionLocation.ServerHost, tier = ModelTier.Frontier),
        )))
        TestServer.start(plugins = listOf(models), script = {
            on("first") { reply("first answer") }
            on("hold") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}""") }
        }, scenario = { agent = agent("local") { it.copy(provider = "test", model = "local", approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val child = value { children.spawn(agent.id, parent.id, ToolCallId("first"), agent.id, "first", private = true) }
            value { children.wait(agent.id, parent.id, listOf(child.sessionId), 5) }
            value { children.send(agent.id, parent.id, child.sessionId, ToolCallId("next"), "hold") }
            untilReady { server.harness.log.pending(child.sessionId).isNotEmpty() }
            value { children.cancel(agent.id, parent.id, child.sessionId) }
            untilReady { children.list(agent.id, parent.id).getValue(child.sessionId).status == SubAgentStatus.Cancelled }

            val client = server.client(token = server.auth.sessionToken()); client.connect()
            val tool = children.tools(agent.id, parent.id).single { it.descriptor.name == "agents_wait" }
            val delivery = async { tool.execute(buildJsonObject {
                putJsonArray("session_ids") { add(child.sessionId.value) }
                put("turn_id", child.turnId)
            }, "delivery") }
            untilReady { server.harness.log.pending(parent.id).any { it is PendingInteraction.Approval } }
            val approval = server.harness.log.pending(parent.id).filterIsInstance<PendingInteraction.Approval>().single()
            assertTrue(client.call(Api.Respond, RespondParams(parent.id, approval.id, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
            assertContains(delivery.await(), "first answer")
            assertTrue(server.harness.store.sessions(agent.id).none { it.id == child.sessionId })
            assertTrue(children.list(agent.id, parent.id).getValue(child.sessionId).delivered)
            val updates = mutableListOf<SessionChange>()
            val subscriber = SessionLog.Subscriber { updates.add(it.change); true }
            server.harness.log.subscribe(agent.id, parent.id, null, 100, subscriber)
            server.harness.log.unsubscribe(parent.id, subscriber)
            assertTrue(updates.filterIsInstance<SessionChange.Live>().single().state.children.getValue(child.sessionId).delivered)
            for (steer in listOf(false, true)) {
                assertEquals(SubAgentError.Unknown(child.sessionId), either {
                    children.send(agent.id, parent.id, child.sessionId, ToolCallId("deleted"), "again", steer)
                }.leftOrNull())
            }
        }
    }

    @Test fun parentCancellationChildCancellationDepthAndSharedBudget() = runBlocking {
        lateinit var agent: Agent
        TestServer.start(script = {
            on("hold") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Wait","kind":"text"}]}""") }
        }, scenario = { agent = agent("work") { it.copy(approvals = "full") } }).use { server ->
            val parent = server.startSession(agent.id)
            val children = server.harness.subAgents!!
            val first = value { children.spawn(agent.id, parent.id, ToolCallId("one"), agent.id, "hold") }
            val grandchild = value { children.spawn(agent.id, first.sessionId, ToolCallId("two"), agent.id, "hold") }
            assertEquals(SubAgentError.Depth, either { children.spawn(agent.id, grandchild.sessionId, ToolCallId("three"), agent.id, "hold") }.leftOrNull())
            val client = server.client(token = server.auth.sessionToken()); client.connect()
            client.call(Api.Stop, StopParams(first.sessionId))
            assertEquals(SubAgentStatus.Cancelled, value { children.wait(agent.id, parent.id, listOf(first.sessionId), 5) }.single().status)
            assertEquals(SubAgentStatus.Cancelled, value { children.wait(agent.id, first.sessionId, listOf(grandchild.sessionId), 5) }.single().status)
            assertEquals(SubAgentError.Stopped, either {
                children.send(agent.id, parent.id, first.sessionId, ToolCallId("steer"), "adjust", steer = true)
            }.leftOrNull())
            client.call(Api.Send, SendParams(agent.id, first.sessionId, "restart"))
            untilReady { children.list(agent.id, parent.id)[first.sessionId]?.status == SubAgentStatus.Completed }
            val next = value { children.spawn(agent.id, parent.id, ToolCallId("next"), agent.id, "hold") }
            try { children.consume(parent.id); repeat(64) { children.consume(next.sessionId) }; fail("Budget should cancel the tree") } catch (_: CancellationException) { }
            assertEquals(SubAgentStatus.Cancelled, value { children.wait(agent.id, parent.id, listOf(next.sessionId), 5) }.single().status)
            val activeParent = server.startSession(agent.id, "hold")
            val activeChild = value { children.spawn(agent.id, activeParent.id, ToolCallId("active"), agent.id, "hold") }
            client.call(Api.Stop, StopParams(activeParent.id))
            assertEquals(SubAgentStatus.Cancelled, value { children.wait(agent.id, activeParent.id, listOf(activeChild.sessionId), 5) }.single().status)
        }
    }
}
