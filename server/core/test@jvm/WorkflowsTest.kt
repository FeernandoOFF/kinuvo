package kinuvo.server

import ai.koog.prompt.message.MessagePart
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.client.KinuvoClient
import kinuvo.client.KinuvoException
import kinuvo.protocol.model.ProviderModelRef
import kinuvo.protocol.surface.*
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.*
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.session.*
import kinuvo.protocol.workflow.*
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileStore
import kinuvo.server.store.WorkflowData
import kinuvo.testing.server.FakeModels
import kinuvo.testing.server.FakeModelProvider
import kinuvo.protocol.model.ModelInfo
import kinuvo.testing.server.FakeTool
import kinuvo.testing.server.FakeTools
import kinuvo.testing.server.TestServer
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.io.files.Path
import java.nio.file.Files
import kinuvo.protocol.tool.ToolNames
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowsTest {
    @Test fun surfaceReviewConsumesSubmissionWithoutAnAgentTurn() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val catalog = ProtocolJson.decodeFromString(UiCatalog.serializer(), javaClass.getResource("/a2ui-basic.json")!!.readText())
            server.harness.uiCatalogs.connect(this, listOf(catalog))
            val c = client(server)
            val surface = Json.parseToJsonElement("""{"surfaceId":"review","catalogId":"${catalog.catalogId}","components":[{"id":"root","component":"Text","text":"Review"}],"dataModel":{"name":"old"}}""").jsonObject
            c.run(c.call(Api.CreateWorkflow, workflow(WorkflowStep.Review(StepId("r"), "Check", surface = surface))).id)
            val waiting = await(c, WorkflowId("test")) { it.steps[StepId("r")]?.status == StepStatus.WaitingApproval }
            val session = waiting.steps.getValue(StepId("r")).sessionId!!
            val call = server.harness.store.entries(agent, session).filterIsInstance<SessionEntry.ToolCall>().single()
            val value = buildJsonObject { put("name", "edited") }
            c.call(Api.Send, SendParams(agent, session, "Submit", action = SurfaceAction(call.id, "review", "submit", "root", JsonObject(emptyMap()), value)))
            assertEquals(value.toString(), finished(c).steps.getValue(StepId("r")).output)
            assertTrue(server.prompts.inputs.isEmpty())
        }
    }
    @Test fun arrayReviewRebuildsTypedItems() {
        for (input in listOf("""[{"name":"a","n":2,"ok":true},{"name":"b","n":3,"ok":false}]""", """{"rows":[{"name":"a","n":2,"ok":true},{"name":"b","n":3,"ok":false}]}""")) {
            val form = ReviewForm.of(input)
            assertEquals(6, form.questions.size)
            assertEquals("Item 1 · name", form.questions.first().label)
            val answers = form.questions.associate { it.id to it.default.orEmpty() }.toMutableMap()
            answers["items.0.n"] = listOf("7")
            assertEquals(Json.parseToJsonElement(input.replace("2", "7")), Json.parseToJsonElement(form.output(answers)))
        }
        assertEquals(1, ReviewForm.of(JsonArray(List(31) { buildJsonObject { put("n", it) } }).toString()).questions.size)
    }
    private val agent = AgentId("work")
    private fun branch(from: String, to: String, on: Boolean) = WorkflowEdge(StepId(from), StepId(to), on)
    private fun turn(id: String, prompt: String = id) = WorkflowStep.AgentTurn(StepId(id), agent, prompt)
    private fun edge(from: String, to: String) = WorkflowEdge(StepId(from), StepId(to))
    private fun workflow(vararg steps: WorkflowStep, edges: List<WorkflowEdge> = emptyList()) =
        Workflow(WorkflowId("test"), "Test", steps.toList(), edges, agentId = agent)
    private fun tool(id: String, name: String, args: JsonObject = JsonObject(emptyMap())) = WorkflowStep.Tool(StepId(id), name, args)
    private val fakes = FakeTools(
        FakeTool("make") { """{"count":2,"items":[{"name":"a"},{"name":"b"}],"who":"Bob"}""" },
        FakeTool("echo") { it.toString() },
        FakeTool("boom") { error("kaput") },
        FakeTool("deploy", Risk.High, reason = "Pushes to production") { "deployed" },
        FakeTool("word") { "hello" },
        FakeTool("fenced") { "```json\n{\"urgent\":true,\"n\":3}\n```" },
        FakeTool("tick") { (++ticks).toString() },
        FakeTool("flaky") { if (++flakes == 1) error("first try") else "second try" },
    )
    private var ticks = 0
    private var flakes = 0
    private fun condition(id: String, left: String, op: ConditionOp = ConditionOp.Equals, right: String = "true") = WorkflowStep.Condition(StepId(id), left, op, right)
    private suspend fun KinuvoClient.run(id: WorkflowId, input: JsonObject = JsonObject(emptyMap())) = call(Api.RunWorkflow, RunWorkflowParams(id, input))
    private suspend fun finished(c: KinuvoClient) = await(c, WorkflowId("test")) { it.status != WorkflowRunStatus.Running }
    private suspend fun client(server: TestServer) = server.client(token = server.auth.sessionToken()).also { it.connect() }
    private suspend fun await(client: KinuvoClient, id: WorkflowId, predicate: suspend (WorkflowRun) -> Boolean): WorkflowRun = withTimeout(10_000) {
        while (true) {
            client.call(Api.WorkflowRuns, id).firstOrNull { predicate(it) }?.let { return@withTimeout it }
            delay(20)
        }
        error("unreachable")
    }

    @Test fun turnInputsKeepDataOutOfThePrompt() = runBlocking<Unit> {
        val combined = "Summarize\n\n<input name=\"emails\">\nmail data\n</input>"
        TestServer.start(script = { on(combined) { reply("summary") } }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(
                WorkflowStep.Text(StepId("data"), "mail data"),
                turn("summarize", "Summarize").copy(inputs = mapOf("emails" to "{{steps.data.output}}")),
                edges = listOf(edge("data", "summarize")),
            ))
            c.run(w.id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertTrue(server.script.prompts.flatMap { it.messages }.flatMap { it.parts }.filterIsInstance<MessagePart.Text>().any { it.text == combined })
            val message = server.harness.store.entries(agent, done.steps.getValue(StepId("summarize")).sessionId!!).filterIsInstance<SessionEntry.UserMessage>().single()
            assertEquals("Summarize", message.text)
            assertEquals(listOf(MessageInput("emails", "mail data")), message.inputs)
        }
    }

    @Test fun linearOutputAndContinueSession() = runBlocking<Unit> {
        TestServer.start(script = { on("first") { reply("one") }; on("using one") { reply("two") } }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(turn("first"), turn("second", "using {{steps.first.output}}"), edges = listOf(edge("first", "second"))))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status)
            val session = done.steps.getValue(StepId("first")).sessionId!!
            assertEquals(session, done.steps.getValue(StepId("second")).sessionId)
            assertEquals(WorkflowRef(w.id, w.name), server.harness.store.session(agent, session).workflow)
            assertEquals(1, server.harness.store.entries(agent, session).filterIsInstance<SessionEntry.ContextBreak>().size)
            assertEquals("one", done.steps[StepId("first")]?.output)
            assertEquals("two", done.steps[StepId("second")]?.output)
            assertTrue(server.prompts.inputs.any { it.text == "using one" })
            val second = w.steps[1] as WorkflowStep.AgentTurn
            c.call(Api.UpdateWorkflow, w.copy(steps = listOf(w.steps[0], second.copy(session = StepSession.Continue))))
            val started = c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val again = await(c, w.id) { it.id == started.id && it.status != WorkflowRunStatus.Running }
            assertEquals(done.steps[second.id]?.sessionId, again.steps[second.id]?.sessionId)
        }
    }

    @Test fun contextBreakResetsModelHistoryAndContinueKeepsIt() = runBlocking<Unit> {
        TestServer.start(script = { reply("reply") }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(
                turn("a", "old context"), turn("b", "fresh context"),
                turn("c", "continue context").copy(session = StepSession.Continue),
                edges = listOf(edge("a", "b"), edge("b", "c")),
            ))
            c.run(w.id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals(1, done.steps.values.map { it.sessionId }.distinct().size)
            val prompts = server.script.prompts.map { it.toString() }
            assertFalse(prompts[1].contains("old context"))
            assertTrue(prompts[2].contains("fresh context"))
            assertFalse(prompts[2].contains("old context"))
            val session = done.steps.getValue(StepId("a")).sessionId!!
            assertEquals(1, server.harness.store.entries(agent, session).filterIsInstance<SessionEntry.ContextBreak>().size)
            c.call(Api.UpdateWorkflow, w.copy(name = "Renamed"))
            val info = server.harness.store.session(agent, session)
            assertEquals("Renamed", info.title)
            assertEquals(WorkflowRef(w.id, "Renamed"), info.workflow)
        }
    }

    @Test fun agentTurnUsesAndRecordsSessionModel() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(FakeModels(FakeModelProvider("test", "Test", listOf(ModelInfo("override"))))), script = { on("hi") { reply("ok") } }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            val ref = ProviderModelRef("test", "override")
            val w = c.call(Api.CreateWorkflow, workflow(turn("a", "hi").copy(model = ref), WorkflowStep.Model(StepId("m"), "hi", model = ref)))
            c.run(w.id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            val step = done.steps.getValue(StepId("a"))
            assertEquals(ref, step.model)
            assertEquals(ref, done.steps.getValue(StepId("m")).model)
            val settings = server.harness.store.session(agent, step.sessionId!!).settings
            assertNull(settings.model)
            assertNull(settings.provider)
            c.call(Api.UpdateWorkflow, w.copy(steps = listOf(turn("a", "hi").copy(session = StepSession.Continue))))
            val next = c.run(w.id)
            val continued = await(c, w.id) { it.id == next.id && it.status != WorkflowRunStatus.Running }
            assertEquals(step.sessionId, continued.steps.getValue(StepId("a")).sessionId)
            assertNotEquals(ref, continued.steps.getValue(StepId("a")).model)
        }
    }

    @Test fun branchesRunInParallelAndJoinWaitsForBoth() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(WorkflowStep.Approval(StepId("a"), "A"), WorkflowStep.Approval(StepId("b"), "B"),
                turn("join"), edges = listOf(edge("a", "join"), edge("b", "join"))))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val waiting = await(c, w.id) { it.steps.values.count { s -> s.status == StepStatus.WaitingApproval } == 2 }
            suspend fun approve(s: StepRun) {
                // Step state may arrive just before SessionLog publishes the interaction.
                withTimeout(5000) { while (server.harness.log.pending(s.sessionId!!).isEmpty()) yield() }
                assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
            }
            approve(waiting.steps.getValue(StepId("a")))
            val half = await(c, w.id) { it.steps[StepId("a")]?.status == StepStatus.Done }
            assertEquals(StepStatus.Pending, half.steps[StepId("join")]?.status)
            approve(waiting.steps.getValue(StepId("b")))
            assertEquals(WorkflowRunStatus.Done, await(c, w.id) { it.status != WorkflowRunStatus.Running }.status)
        }
    }

    @Test fun rejectionStopsTheGraphAndCancellationResolvesPending() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(WorkflowStep.Approval(StepId("approval"), "Continue?"), turn("after"), edges = listOf(edge("approval", "after"))))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val waiting = await(c, w.id) { it.steps[StepId("approval")]?.status == StepStatus.WaitingApproval }
            val s = waiting.steps.getValue(StepId("approval"))
            withTimeout(5000) { while (server.harness.log.pending(s.sessionId!!).isEmpty()) yield() }
            assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.Deny()))))
            val failed = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Failed, failed.status)
            assertEquals(StepStatus.Skipped, failed.steps[StepId("after")]?.status)
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val next = await(c, w.id) { it.id != waiting.id && it.steps[StepId("approval")]?.status == StepStatus.WaitingApproval }
            assertEquals(WorkflowRunStatus.Cancelled, c.call(Api.CancelWorkflow, next.id).status)
            assertTrue(server.harness.log.pending(next.steps.getValue(StepId("approval")).sessionId!!).isEmpty())
        }
    }

    @Test fun cancellingAgentTurnStopsItsSession() = runBlocking<Unit> {
        TestServer.start(script = { on("wait") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Continue?","kind":"text"}]}"""); reply("later") } }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(turn("wait")))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val waiting = await(c, w.id) { r -> r.steps[StepId("wait")]?.sessionId?.let { server.harness.log.pending(it).isNotEmpty() } == true }
            val cancelled = c.call(Api.CancelWorkflow, waiting.id)
            assertEquals(WorkflowRunStatus.Cancelled, cancelled.status)
            assertEquals(StepStatus.Cancelled, cancelled.steps[StepId("wait")]?.status)
            assertTrue(server.harness.log.pending(cancelled.steps.getValue(StepId("wait")).sessionId!!).isEmpty())
        }
    }

    private val deployed = java.util.concurrent.atomic.AtomicInteger()
    private val restartTools = FakeTools(FakeTool("deploy", Risk.High, reason = "Pushes to production") { "deployed${deployed.incrementAndGet()}" })

    /** Starts a Server on a home whose store already holds [w] with one Running run in [steps], as left by a killed Server. */
    private suspend fun <T> restarted(w: Workflow, steps: Map<String, StepRun>, session: Boolean = true, block: suspend (TestServer, KinuvoClient, SessionId?) -> T): T {
        val withSession = session
        val home = Files.createTempDirectory("workflows-recovery").toFile()
        try {
            var sid: SessionId? = null
            return TestServer.start(home = Path(home.path), plugins = listOf(restartTools), script = { on("first") { reply("again") } }, scenario = {
                val a = agent("work")
                if (withSession) sid = session(a).id
                val seeded = w.steps.associate { it.id to StepRun() }.plus(steps.mapKeys { StepId(it.key) }.mapValues { (_, s) -> if (s.sessionId != null) s.copy(sessionId = sid) else s })
                FileStore(AgentFiles(Path(home.path))).saveWorkflows(WorkflowData(listOf(w), listOf(WorkflowRun(WorkflowRunId("interrupted"), w.id, WorkflowRunStatus.Running, 1, steps = seeded, workflow = w))))
            }).use { server -> block(server, client(server), sid) }
        } finally { home.deleteRecursively() }
    }

    private val interrupted = "Interrupted by a Server restart"
    private val placeholder = SessionId("placeholder")

    @Test fun runWaitingOnAnApprovalStepSurvivesARestart() = runBlocking<Unit> {
        val w = workflow(turn("first"), WorkflowStep.Approval(StepId("approve"), "Wait"), edges = listOf(edge("first", "approve")))
        restarted(w, mapOf("first" to StepRun(StepStatus.Done, output = "retained"),
            "approve" to StepRun(StepStatus.WaitingApproval, placeholder, interactionId = InteractionId("old")))) { server, c, sid ->
            val waiting = await(c, w.id) { it.steps[StepId("approve")]?.status == StepStatus.WaitingApproval && it.steps[StepId("approve")]?.interactionId != InteractionId("old") }
            assertEquals(WorkflowRunStatus.Running, waiting.status)
            val s = waiting.steps.getValue(StepId("approve"))
            assertEquals(sid, s.sessionId)
            withTimeout(5000) { while (server.harness.log.pending(sid!!).isEmpty()) yield() }
            assertEquals(listOf(s.interactionId), server.harness.log.pending(sid!!).map { it.id })
            assertTrue(c.call(Api.Respond, RespondParams(sid, s.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("retained", done.steps[StepId("first")]?.output)
            assertTrue(server.prompts.inputs.none { it.text == "first" })
            assertEquals(1, server.harness.store.sessions(agent).size)
            assertTrue(server.harness.log.pending(sid).isEmpty())
        }
    }

    @Test fun toolWaitingForItsApprovalSurvivesARestartAndRunsOnce() = runBlocking<Unit> {
        deployed.set(0)
        val w = workflow(tool("a", "deploy"), tool("b", "deploy"), edges = listOf(edge("a", "b")))
        restarted(w, mapOf("a" to StepRun(StepStatus.WaitingApproval, placeholder, interactionId = InteractionId("old")))) { server, c, sid ->
            suspend fun approve(step: String): StepRun {
                val s = await(c, w.id) { it.steps[StepId(step)]?.status == StepStatus.WaitingApproval && it.steps[StepId(step)]?.interactionId != InteractionId("old") }.steps.getValue(StepId(step))
                withTimeout(5000) { while (server.harness.log.pending(s.sessionId!!).isEmpty()) yield() }
                assertEquals(listOf(s.interactionId), server.harness.log.pending(s.sessionId!!).map { it.id })
                assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
                return s
            }
            assertEquals(sid, approve("a").sessionId)
            await(c, w.id) { it.steps[StepId("a")]?.status == StepStatus.Done }
            assertEquals(1, deployed.get())
            approve("b")
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("deployed1", done.steps[StepId("a")]?.output)
            assertEquals(2, deployed.get())
        }
    }

    @Test fun aCallMadeByARunningToolMarksTheStepStarted() = runBlocking<Unit> {
        val script = object : kinuvo.server.plugin.Tool {
            override val descriptor = kinuvo.server.plugin.toolSpec("script", "Calls deploy")
            override suspend fun execute(args: JsonObject) = error("unused")
            override suspend fun execute(args: JsonObject, callId: String, run: kinuvo.server.plugin.ToolRun) = run.call("deploy", "{}").output
        }
        val tools = object : kinuvo.server.plugin.ServerPlugin {
            override val definition = kinuvo.protocol.plugin.PluginDefinition("test.script", "Script")
            override suspend fun prepare(run: kinuvo.server.plugin.RunContext, activate: Boolean) = kinuvo.server.plugin.PluginTools(listOf(script))
        }
        TestServer.start(plugins = listOf(fakes, tools), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "script")))
            c.run(w.id)
            suspend fun approve(after: InteractionId?): StepRun {
                val s = await(c, w.id) { it.steps[StepId("a")]?.let { a -> a.status == StepStatus.WaitingApproval && a.interactionId != after } == true }.steps.getValue(StepId("a"))
                assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
                return s
            }
            val own = approve(null)
            assertFalse(own.started)
            assertTrue(approve(own.interactionId).started)
            assertEquals("deployed", finished(c).steps[StepId("a")]?.output)
        }
    }

    @Test fun aToolWaitingOnACallItMadeFailsOnRestartInsteadOfReplaying() = runBlocking<Unit> {
        deployed.set(0)
        val w = workflow(tool("a", "deploy"))
        restarted(w, mapOf("a" to StepRun(StepStatus.WaitingApproval, placeholder, interactionId = InteractionId("old"), started = true))) { _, c, _ ->
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Failed, done.status)
            assertEquals("Interrupted by a Server restart", done.steps[StepId("a")]?.error)
            assertEquals(0, deployed.get())
        }
    }

    @Test fun interruptedRunningStepsFailTheRunWhileModelStepsAreAskedAgain() = runBlocking<Unit> {
        for (kind in listOf(turn("a"), tool("a", "deploy"))) {
            val w = workflow(kind, turn("b"), edges = listOf(edge("a", "b")))
            restarted(w, mapOf("a" to StepRun(StepStatus.Running, placeholder))) { server, c, sid ->
                val failed = await(c, w.id) { it.status != WorkflowRunStatus.Running }
                assertEquals(WorkflowRunStatus.Failed, failed.status)
                assertEquals(StepStatus.Failed, failed.steps[StepId("a")]?.status)
                assertEquals(interrupted, failed.steps[StepId("a")]?.error)
                assertEquals(sid, failed.steps[StepId("a")]?.sessionId)
                assertEquals(StepStatus.Skipped, failed.steps[StepId("b")]?.status)
                assertNotNull(failed.endedAt)
                assertTrue(server.prompts.inputs.isEmpty())
            }
        }
        val m = workflow(WorkflowStep.Model(StepId("m"), "first"))
        restarted(m, mapOf("m" to StepRun(StepStatus.Running)), session = false) { _, c, _ ->
            val done = await(c, m.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("again", done.steps[StepId("m")]?.output)
        }
    }

    @Test fun resumedRunCanBeCancelledAndBlocksOverlappingRuns() = runBlocking<Unit> {
        val w = workflow(WorkflowStep.Approval(StepId("approve"), "Wait"))
        restarted(w, mapOf("approve" to StepRun(StepStatus.WaitingApproval, placeholder, interactionId = InteractionId("old")))) { server, c, sid ->
            val waiting = await(c, w.id) { it.steps[StepId("approve")]?.interactionId.let { id -> id != null && id != InteractionId("old") } }
            withTimeout(5000) { while (server.harness.log.pending(sid!!).isEmpty()) yield() }
            assertEquals("conflict", assertFailsWith<KinuvoException> { c.call(Api.RunWorkflow, RunWorkflowParams(w.id)) }.code)
            val cancelled = c.call(Api.CancelWorkflow, waiting.id)
            assertEquals(WorkflowRunStatus.Cancelled, cancelled.status)
            assertEquals(StepStatus.Cancelled, cancelled.steps[StepId("approve")]?.status)
            assertTrue(server.harness.log.pending(sid!!).isEmpty())
        }
    }

    @Test fun cyclesUnknownEdgesAndInvalidReferencesAreTypedErrors() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val c = client(server)
            for (w in listOf(workflow(turn("a"), edges = listOf(edge("a", "a"))),
                workflow(turn("a"), edges = listOf(edge("a", "missing"))), workflow(turn("a", "{{steps.missing.output}}")),
                workflow(turn("a").copy(inputs = mapOf("data" to "{{steps.missing.output}}"))),
                workflow(turn("a").copy(inputs = mapOf("bad name" to "text"))),
                workflow(WorkflowStep.Model(StepId("a"), "read", inputs = mapOf("data" to "{{steps.missing.output}}"))))) {
                val error = assertFailsWith<KinuvoException> { c.call(Api.CreateWorkflow, w) }
                assertEquals("invalid_params", error.code)
            }
        }
    }

    @Test fun validationReportsIndependentProblemsTogether() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = workflow(
                WorkflowStep.AgentTurn(StepId("turn-1"), AgentId("missing-agent"), "{{steps.x.output}} {{input.missing}}"),
                edges = listOf(edge("turn-1", "zz"), WorkflowEdge(StepId("unknown"), StepId("turn-1"), true)),
            )
            val error = assertFailsWith<KinuvoException> { c.call(Api.CreateWorkflow, w) }
            assertEquals("invalid_params", error.code)
            for (problem in listOf("step turn-1: unknown Agent 'missing-agent'; known ids: ${server.harness.store.agents().take(20).joinToString { it.id.value }} (use an id from the agents action, not a name)",
                "step turn-1: {{steps.x.output}} names x, which is not an ancestor",
                "step turn-1: {{input.missing}} names undeclared input 'missing'",
                "edge turn-1 -> zz: unknown step zz", "edge unknown -> turn-1: unknown step unknown")) {
                assertContains(error.message.orEmpty(), problem)
            }

        }
    }

    @Test fun unknownAgentHintsAreBounded() = runBlocking<Unit> {
        TestServer.start(scenario = { repeat(22) { agent("agent-$it") } }).use { server ->
            val agents = server.harness.store.agents()
            val error = assertFailsWith<KinuvoException> {
                client(server).call(Api.CreateWorkflow, workflow(WorkflowStep.AgentTurn(StepId("turn-1"), AgentId("missing"), "test")))
            }
            val hint = "known ids: ${agents.take(20).joinToString { it.id.value }}, ... (use an id from the agents action, not a name)"
            assertContains(error.message.orEmpty(), hint)
        }
    }

    @Test fun toolStepRunsWithoutASession() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.run(c.call(Api.CreateWorkflow, workflow(tool("a", "echo", buildJsonObject { put("x", 1) }))).id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("""{"x":1}""", done.steps[StepId("a")]?.output)
            assertNull(done.steps[StepId("a")]?.sessionId)
            assertTrue(server.harness.store.sessions(agent).isEmpty())
        }
    }

    @Test fun aShowHtmlStepRendersInTheRunsSessionWithoutADeviceCatalog() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.run(c.call(Api.CreateWorkflow, workflow(tool("t", ToolNames.SHOW_HTML, buildJsonObject { put("title", "T"); put("html", "<p>hi</p>") }))).id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status, done.steps[StepId("t")]?.error)
            assertNotNull(done.steps[StepId("t")]?.sessionId)
        }
    }

    @Test fun failingToolFailsStepAndRun() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.run(c.call(Api.CreateWorkflow, workflow(tool("a", "boom"), tool("b", "echo"), edges = listOf(edge("a", "b")))).id)
            val failed = finished(c)
            assertEquals(WorkflowRunStatus.Failed, failed.status)
            assertEquals(StepStatus.Failed, failed.steps[StepId("a")]?.status)
            assertTrue(failed.steps[StepId("a")]?.error.orEmpty().contains("kaput"))
            assertEquals(StepStatus.Skipped, failed.steps[StepId("b")]?.status)
            c.call(Api.UpdateWorkflow, workflow(tool("a", "missing_tool")))
            c.call(Api.RunWorkflow, RunWorkflowParams(WorkflowId("test")))
            assertEquals(WorkflowRunStatus.Failed, await(c, WorkflowId("test")) { it.id != failed.id && it.status != WorkflowRunStatus.Running }.status)
        }
    }

    @Test fun typedArgumentsAndPathReferencesFeedLaterSteps() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val args = buildJsonObject {
                put("count", "{{steps.a.output.count}}")
                put("name", "{{steps.a.output.items.1.name}}")
                put("text", "has {{steps.a.output.count}} items for {{steps.a.output.who}}")
                put("all", "{{steps.a.output}}")
                put("nested", buildJsonArray { add("{{steps.a.output.items.0}}") })
            }
            c.run(c.call(Api.CreateWorkflow, workflow(tool("a", "make"), tool("b", "echo", args), edges = listOf(edge("a", "b")))).id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            val out = Json.parseToJsonElement(done.steps.getValue(StepId("b")).output!!).jsonObject
            assertEquals(JsonPrimitive(2), out["count"])
            assertEquals(JsonPrimitive("b"), out["name"])
            assertEquals(JsonPrimitive("has 2 items for Bob"), out["text"])
            assertEquals(2, out.getValue("all").jsonObject["count"]?.jsonPrimitive?.int)
            assertEquals("a", out.getValue("nested").jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)
        }
    }

    @Test fun badPathFailsNamingTheReference() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.run(c.call(Api.CreateWorkflow, workflow(tool("a", "make"),
                tool("b", "echo", buildJsonObject { put("x", "{{steps.a.output.items.9.name}}") }), edges = listOf(edge("a", "b")))).id)
            val failed = finished(c)
            assertEquals(WorkflowRunStatus.Failed, failed.status)
            assertTrue(failed.steps[StepId("b")]?.error.orEmpty().contains("{{steps.a.output.items.9.name}}"))
        }
    }

    @Test fun modelStepRepliesWithoutASession() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), script = { on("hello Bob\n\n<input name=\"count\">\n2\n</input>") { reply("greeted") } }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.run(c.call(Api.CreateWorkflow, workflow(tool("a", "make"),
                WorkflowStep.Model(StepId("m"), "hello {{steps.a.output.who}}", system = "Be brief for {{steps.a.output.count}}", inputs = mapOf("count" to "{{steps.a.output.count}}")), edges = listOf(edge("a", "m")))).id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("greeted", done.steps[StepId("m")]?.output)
            assertEquals(server.harness.store.agents().first { it.id == agent }.config.let { ProviderModelRef(it.provider, it.model) }, done.steps[StepId("m")]?.model)
            assertNull(done.steps[StepId("m")]?.sessionId)
            assertTrue(server.harness.store.sessions(agent).isEmpty())
            assertTrue("Be brief for 2" in server.script.prompts.last().toString())
        }
    }

    @Test fun toolNeedingApprovalWaitsInALazySessionThenContinuesOrFails() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "deploy")))
            suspend fun answer(decision: ApprovalDecision): WorkflowRun {
                val started = c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
                val waiting = await(c, w.id) { it.id == started.id && it.steps[StepId("a")]?.status == StepStatus.WaitingApproval }
                val s = waiting.steps.getValue(StepId("a"))
                withTimeout(5000) { while (server.harness.log.pending(s.sessionId!!).isEmpty()) yield() }
                assertTrue(server.harness.store.sessions(agent).isNotEmpty())
                assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Approval(decision))))
                return await(c, w.id) { it.id == started.id && it.status != WorkflowRunStatus.Running }
            }
            val approved = answer(ApprovalDecision.AllowOnce)
            assertEquals(WorkflowRunStatus.Done, approved.status)
            assertEquals("deployed", approved.steps[StepId("a")]?.output)
            assertNotNull(approved.steps[StepId("a")]?.sessionId)
            assertTrue(server.harness.log.pending(approved.steps.getValue(StepId("a")).sessionId!!).isEmpty())
            assertEquals(WorkflowRunStatus.Failed, answer(ApprovalDecision.Deny()).status)
        }
    }

    private fun review(id: String, value: String) = WorkflowStep.Review(StepId(id), "Check it", value)

    private suspend fun waitingReview(server: TestServer, c: KinuvoClient, started: WorkflowRunId? = null): Pair<StepRun, PendingInteraction.Questions> {
        val s = await(c, WorkflowId("test")) { (started == null || it.id == started) && it.steps[StepId("r")]?.status == StepStatus.WaitingApproval }.steps.getValue(StepId("r"))
        withTimeout(5000) { while (server.harness.log.pending(s.sessionId!!).isEmpty()) yield() }
        return s to server.harness.log.pending(s.sessionId!!).single() as PendingInteraction.Questions
    }

    @Test fun reviewShowsTypedPrefilledFieldsAndTheEditedAnswersFeedLaterSteps() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val value = """{"title":"Hi","body":"line one\nline two","count":2,"ok":true,"items":[{"name":"a"}]}"""
            val args = buildJsonObject { put("n", "{{steps.r.output.count}}"); put("ok", "{{steps.r.output.ok}}"); put("first", "{{steps.r.output.items.0.name}}") }
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "echo", buildJsonObject { put("x", value) }), review("r", "{{steps.a.output.x}}"), tool("b", "echo", args),
                edges = listOf(edge("a", "r"), edge("r", "b"))))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val (s, asked) = waitingReview(server, c)
            assertEquals("Check it", asked.title)
            assertEquals(listOf("title", "body", "count", "ok", "items"), asked.questions.map { it.id })
            assertEquals(listOf(QuestionKind.Text, QuestionKind.LongText, QuestionKind.Number, QuestionKind.YesNo, QuestionKind.LongText), asked.questions.map { it.kind })
            assertEquals(listOf("Hi"), asked.questions[0].default)
            assertEquals(listOf("2"), asked.questions[2].default)
            assertEquals(listOf("Yes"), asked.questions[3].default)
            assertEquals(JsonArray(listOf(buildJsonObject { put("name", "a") })), Json.parseToJsonElement(asked.questions[4].default.single()))
            val answers = mapOf("title" to listOf("Hello"), "body" to listOf("x"), "count" to listOf("3"), "ok" to listOf("No"), "items" to listOf("""[{"name":"z"}]"""))
            assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Questions(answers))))
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            val out = Json.parseToJsonElement(done.steps.getValue(StepId("r")).output!!).jsonObject
            assertEquals(JsonPrimitive(3), out["count"])
            assertEquals(JsonPrimitive(false), out["ok"])
            assertEquals(JsonPrimitive("Hello"), out["title"])
            assertEquals("z", out.getValue("items").jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)
            val next = Json.parseToJsonElement(done.steps.getValue(StepId("b")).output!!).jsonObject
            assertEquals(JsonPrimitive(3), next["n"])
            assertEquals(JsonPrimitive(false), next["ok"])
            assertEquals(JsonPrimitive("z"), next["first"])
            assertTrue(server.harness.log.pending(s.sessionId!!).isEmpty())
        }
    }

    @Test fun reviewWithPlainTextRoundTripsThroughOneField() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.run(c.call(Api.CreateWorkflow, workflow(review("r", "Draft: {{steps.a.output.who}}"), tool("a", "make"), edges = listOf(edge("a", "r")))).id)
            val (s, asked) = waitingReview(server, c)
            assertEquals(listOf(QuestionKind.LongText), asked.questions.map { it.kind })
            assertEquals(listOf("Draft: Bob"), asked.questions.single().default)
            assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Questions(mapOf(asked.questions.single().id to listOf("Draft: Alice"))))))
            assertEquals("Draft: Alice", finished(c).steps.getValue(StepId("r")).output)
        }
    }

    @Test fun decliningOrBreakingAReviewFieldFailsTheRun() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(review("r", """{"a":{"b":1}}"""), turn("after"), edges = listOf(edge("r", "after"))))
            val first = c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val (s, _) = waitingReview(server, c, first.id)
            assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Questions(null))))
            val declined = finished(c)
            assertEquals(WorkflowRunStatus.Failed, declined.status)
            assertEquals("Review declined", declined.steps.getValue(StepId("r")).error)
            assertEquals(StepStatus.Skipped, declined.steps[StepId("after")]?.status)
            val second = c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val (t, _) = waitingReview(server, c, second.id)
            assertTrue(c.call(Api.Respond, RespondParams(t.sessionId!!, t.interactionId!!, InteractionAnswer.Questions(mapOf("a" to listOf("{nope"))))))
            val broken = await(c, w.id) { it.id == second.id && it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Failed, broken.status)
            assertEquals("Review field 'a' is not valid JSON", broken.steps.getValue(StepId("r")).error)
        }
    }

    @Test fun reviewWaitingOnARestartAsksAgainInTheSameSession() = runBlocking<Unit> {
        val w = workflow(review("r", """{"name":"x"}"""))
        restarted(w, mapOf("r" to StepRun(StepStatus.WaitingApproval, placeholder, interactionId = InteractionId("old")))) { server, c, sid ->
            val s = await(c, w.id) { it.steps[StepId("r")]?.status == StepStatus.WaitingApproval && it.steps[StepId("r")]?.interactionId != InteractionId("old") }.steps.getValue(StepId("r"))
            assertEquals(sid, s.sessionId)
            withTimeout(5000) { while (server.harness.log.pending(sid!!).isEmpty()) yield() }
            val pending = server.harness.log.pending(sid!!).single()
            assertEquals(s.interactionId, pending.id)
            assertTrue(pending is PendingInteraction.Questions)
            assertTrue(c.call(Api.Respond, RespondParams(sid, s.interactionId!!, InteractionAnswer.Questions(mapOf("name" to listOf("y"))))))
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("""{"name":"y"}""", done.steps.getValue(StepId("r")).output)
            assertEquals(1, server.harness.store.sessions(agent).size)
        }
    }

    @Test fun cancellingAToolWaitingForApprovalResolvesIt() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "deploy")))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val waiting = await(c, w.id) { it.steps[StepId("a")]?.status == StepStatus.WaitingApproval }
            val session = waiting.steps.getValue(StepId("a")).sessionId!!
            withTimeout(5000) { while (server.harness.log.pending(session).isEmpty()) yield() }
            val cancelled = c.call(Api.CancelWorkflow, waiting.id)
            assertEquals(StepStatus.Cancelled, cancelled.steps[StepId("a")]?.status)
            assertTrue(server.harness.log.pending(session).isEmpty())
        }
    }

    @Test fun saveRejectsMissingRunsAsAgentAndNonAncestorReferences() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val withoutAgent = listOf(workflow(tool("a", "echo")).copy(agentId = null),
                workflow(WorkflowStep.Model(StepId("m"), "hi")).copy(agentId = null),
                workflow(tool("a", "echo")).copy(agentId = AgentId("nobody")),
                workflow(tool("a", "echo"), tool("b", "echo", buildJsonObject { put("x", "{{steps.a.output}}") })),
                workflow(tool("a", "echo", buildJsonObject { put("x", buildJsonArray { add("{{steps.a.output.k}}") }) })),
                workflow(WorkflowStep.Model(StepId("m"), "hi", system = "{{steps.m.output}}")))
            for (w in withoutAgent) assertEquals("invalid_params", assertFailsWith<KinuvoException> { c.call(Api.CreateWorkflow, w) }.code)
        }
    }

    @Test fun scheduledWorkflowFiresThroughScheduler() = runBlocking<Unit> {
        TestServer.start(schedule = true, scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(turn("scheduled")).copy(trigger = Trigger.Once(1)))
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertFalse(c.call(Api.Workflow, w.id).enabled)
        }
    }

    @Test fun repositoryReceivesLiveRunsFromRealServer() = runBlocking<Unit> {
        val main = newSingleThreadContext("workflow-client")
        Dispatchers.setMain(main)
        try {
            TestServer.start(scenario = { agent("work") }).use { server ->
                withContext(main) {
                    val prefs = object : Preferences {
                        private val values = mutableMapOf<String, String>()
                        override fun get(key: String) = values[key]
                        override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
                        override fun clear() { values.clear() }
                    }
                    val reader = object : DeviceReader {
                        override val kinds = emptyList<String>()
                        override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                        override fun read(requestJson: String, completion: (String) -> Unit) = error("unused")
                    }
                    val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(prefs, reader))
                    try {
                        val c = client(server)
                        owner.open(c, "memory://test-server"); owner.activate()
                        val repo = owner.workflows
                        val w = repo.save(workflow(turn("live")), create = true)
                        repo.load(w.id)
                        withTimeout(5000) { repo.state.first { it.workflows.any { it.id == w.id } && !it.loading } }
                        repo.run(w.id)
                        val done = withTimeout(10000) { repo.state.first { it.runs[w.id]?.any { r -> r.status == WorkflowRunStatus.Done } == true } }
                        assertEquals("Scripted reply", done.runs.getValue(w.id).first().steps[StepId("live")]?.output)
                    } finally { owner.close() }
                }
            }
        } finally { Dispatchers.resetMain(); main.close() }
    }

    @Test fun inputsFillReferencesWithDefaultsAndKeepTheirTypes() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val args = buildJsonObject { put("who", "{{input.who}}"); put("n", "{{input.n}}"); put("text", "{{input.who}} x{{input.n}} {{input.o.missing}}!"); put("deep", "{{input.o.k}}"); put("gone", "{{input.o.missing}}") }
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "echo", args)).copy(inputs = listOf(WorkflowInput("who", default = "Bob"), WorkflowInput("n", default = "2"), WorkflowInput("o", default = "{}"))))
            val started = c.run(w.id, buildJsonObject { put("who", "Ann"); putJsonObject("o") { put("k", true) } })
            assertEquals(JsonPrimitive(2), started.input["n"])
            val done = finished(c)
            assertEquals("""{"who":"Ann","n":2,"text":"Ann x2 !","deep":true,"gone":null}""", done.steps[StepId("a")]?.output)
            assertEquals(done.steps[StepId("a")]?.output, done.output)
            val bad = assertFailsWith<KinuvoException> { c.call(Api.UpdateWorkflow, w.copy(inputs = listOf(WorkflowInput("a b")))) }
            assertEquals("invalid_params", bad.code)
        }
    }

    @Test fun aTextStepStartsTheFlowWithItsRenderedText() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val text = WorkflowStep.Text(StepId("text-1"), "{\"to\":\"{{input.who}}\",\"on\":\"{{now.date}}\"}")
            val w = c.call(Api.CreateWorkflow, workflow(text, tool("a", "echo", buildJsonObject { put("to", "{{steps.text-1.output.to}}"); put("now", "{{now}}") }), edges = listOf(edge("text-1", "a")))
                .copy(timeZone = "Europe/Madrid", inputs = listOf(WorkflowInput("who", default = "Ann"))))
            c.run(w.id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            val out = Json.parseToJsonElement(done.steps[StepId("a")]?.output!!).jsonObject
            assertEquals("Ann", out["to"]?.jsonPrimitive?.content)
            assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+0[12]:00").matches(out["now"]!!.jsonPrimitive.content), out.toString())
        }
    }

    @Test fun nowReferencesReadTheTimeInTheWorkflowsZone() {
        val now = nowValue(1_763_380_800_123, kotlinx.datetime.TimeZone.of("America/New_York"))
        val values = WorkflowValues({ null }, now = now)
        assertEquals("2025-11-17T07:00:00-05:00 Monday 07:00 2025-11-17 America/New_York 2025-11-17T12:00:00Z 1763380800123",
            WorkflowReferences.render("{{now}} {{now.weekday}} {{now.time}} {{now.date}} {{now.timezone}} {{now.utc}} {{now.timestamp}}", values))
    }

    @Test fun fencedJsonOutputIsReadLikeJson() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.run(c.call(Api.CreateWorkflow, workflow(tool("a", "fenced"), tool("b", "echo", buildJsonObject { put("n", "{{steps.a.output.n}}"); put("all", "{{steps.a.output}}") }),
                edges = listOf(edge("a", "b")))).id)
            assertEquals("""{"n":3,"all":{"urgent":true,"n":3}}""", finished(c).steps[StepId("b")]?.output)
            c.call(Api.UpdateWorkflow, workflow(tool("a", "word"), tool("b", "echo", buildJsonObject { put("all", "{{steps.a.output}}") }), edges = listOf(edge("a", "b"))))
            val second = c.run(WorkflowId("test"))
            assertEquals("""{"all":"hello"}""", await(c, WorkflowId("test")) { it.id == second.id && it.status != WorkflowRunStatus.Running }.steps[StepId("b")]?.output)
        }
    }

    @Test fun conditionFollowsOneBranchSkipsTheOtherAndMergesAfter() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "fenced"), condition("if", "{{steps.a.output.urgent}}"),
                tool("yes", "echo", buildJsonObject { put("p", "urgent") }), tool("no", "echo", buildJsonObject { put("p", "calm") }), tool("after-no", "echo"),
                tool("merge", "echo", buildJsonObject { put("yes", "{{steps.yes.output.p}}"); put("no", "{{steps.no.output.p}}"); put("text", "[{{steps.no.output}}]") }),
                edges = listOf(edge("a", "if"), branch("if", "yes", true), branch("if", "no", false), edge("no", "after-no"), edge("yes", "merge"), edge("no", "merge"))))
            c.run(w.id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("true", done.steps[StepId("if")]?.output)
            assertEquals(StepStatus.Done, done.steps[StepId("yes")]?.status)
            assertEquals(StepStatus.Skipped, done.steps[StepId("no")]?.status)
            assertEquals(StepStatus.Skipped, done.steps[StepId("after-no")]?.status)
            assertEquals("""{"yes":"urgent","no":null,"text":"[]"}""", done.steps[StepId("merge")]?.output)
            assertEquals(done.steps[StepId("merge")]?.output, done.output)
            val bad = assertFailsWith<KinuvoException> { c.call(Api.UpdateWorkflow, w.copy(edges = listOf(branch("a", "if", true)))) }
            assertEquals("invalid_params", bad.code)
        }
    }

    @Test fun conditionOperatorsCompareTextNumbersAndEmptiness() {
        fun test(left: String, op: ConditionOp, right: String = "") = WorkflowReferences.test(left, op, right)
        assertTrue(test(" yes ", ConditionOp.Equals, "yes"))
        assertTrue(test("2.0", ConditionOp.Equals, "2"))
        assertTrue(test("a", ConditionOp.NotEquals, "b"))
        assertTrue(test("hello world", ConditionOp.Contains, "lo w"))
        assertTrue(test("hello", ConditionOp.NotContains, "z"))
        for (empty in listOf("", "  ", "null", "[]", "{}", "false")) assertTrue(test(empty, ConditionOp.Empty), empty)
        assertTrue(test("0", ConditionOp.NotEmpty))
        assertTrue(test("10", ConditionOp.GreaterThan, "9"))
        assertTrue(test("-1", ConditionOp.LessThan, "0.5"))
        assertTrue(assertFailsWith<IllegalStateException> { test("ten", ConditionOp.GreaterThan, "9") }.message!!.contains("left side"))
    }

    @Test fun aRunWithSeveralEndsReportsEachByStep() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.run(c.call(Api.CreateWorkflow, workflow(tool("a", "make"), tool("b", "tick"))).id)
            val out = Json.parseToJsonElement(finished(c).output!!).jsonObject
            assertEquals(JsonPrimitive("Bob"), out.getValue("a").jsonObject["who"])
            assertEquals(JsonPrimitive(1), out["b"])
        }
    }

    @Test fun retryConflictsWithAnActiveRunAndReevaluatesCancelledSteps() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(WorkflowStep.Approval(StepId("a"), "Continue?")))
            val first = c.run(w.id)
            await(c, w.id) { it.id == first.id && it.steps[StepId("a")]?.status == StepStatus.WaitingApproval }
            val cancelled = c.call(Api.CancelWorkflow, first.id)
            val active = c.run(w.id)
            assertEquals("conflict", assertFailsWith<KinuvoException> { c.call(Api.RetryWorkflow, cancelled.id) }.code)
            c.call(Api.CancelWorkflow, active.id)
            val retry = c.call(Api.RetryWorkflow, cancelled.id)
            assertEquals(cancelled.input, retry.input)
            assertEquals(StepStatus.Pending, retry.steps[StepId("a")]?.status)
            c.call(Api.CancelWorkflow, retry.id)
        }
    }

    @Test fun retryKeepsDoneStepsAndRunsTheRest() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "tick"), tool("b", "flaky"), tool("c", "echo", buildJsonObject { put("a", "{{steps.a.output}}"); put("b", "{{steps.b.output}}"); put("in", "{{input.v}}") }),
                edges = listOf(edge("a", "b"), edge("b", "c"))).copy(inputs = listOf(WorkflowInput("v"))))
            val first = c.run(w.id, buildJsonObject { put("v", 7) })
            val failed = await(c, w.id) { it.id == first.id && it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Failed, failed.status)
            assertNull(failed.output)
            c.call(Api.UpdateWorkflow, w.copy(steps = listOf(tool("a", "boom"))  , edges = emptyList()))
            val again = c.call(Api.RetryWorkflow, failed.id)
            assertEquals(failed.id, again.retryOf)
            assertEquals(StepStatus.Done, again.steps[StepId("a")]?.status)
            val done = await(c, w.id) { it.id == again.id && it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("""{"a":1,"b":"second try","in":7}""", done.output)
            assertEquals(1, ticks)
            assertEquals("conflict", assertFailsWith<KinuvoException> { c.call(Api.RetryWorkflow, done.id) }.code)
            assertEquals("not_found", assertFailsWith<KinuvoException> { c.call(Api.RetryWorkflow, WorkflowRunId("nope")) }.code)
        }
    }

    @Test fun aRunAsksInOneNamedSessionThatRecordsTheAnswers() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(WorkflowStep.Approval(StepId("one"), "First?"), WorkflowStep.Approval(StepId("two"), "Second?"), review("r", "note"),
                edges = listOf(edge("one", "two"), edge("two", "r"))))
            c.run(w.id)
            suspend fun waiting(id: String) = await(c, w.id) { it.steps[StepId(id)]?.status == StepStatus.WaitingApproval }.steps.getValue(StepId(id))
            val one = waiting("one")
            assertTrue(c.call(Api.Respond, RespondParams(one.sessionId!!, one.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
            val two = waiting("two")
            assertTrue(c.call(Api.Respond, RespondParams(two.sessionId!!, two.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
            val r = waiting("r")
            assertTrue(c.call(Api.Respond, RespondParams(r.sessionId!!, r.interactionId!!, InteractionAnswer.Questions(mapOf("value" to listOf("edited"))))))
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals(setOf(one.sessionId), setOf(two.sessionId, r.sessionId))
            assertEquals("Test", server.harness.store.session(agent, one.sessionId!!).title)
            suspend fun notes() = server.harness.store.entries(agent, one.sessionId!!).filterIsInstance<SessionEntry.Notice>().also { n -> assertTrue(n.all { it.code == "workflow" }) }.map { it.message }
            withTimeout(5000) { while (notes().last() != "Run done") delay(20) }
            assertEquals(listOf("Run started", "Allowed: “First?”", "Allowed: “Second?”", "Review submitted: “Check it”", "Run done"), notes())
            assertTrue(server.harness.store.entries(agent, one.sessionId!!).none { it is SessionEntry.ToolCall })
            // The next run continues in the same Session.
            val second = c.run(w.id)
            val again = await(c, w.id) { it.id == second.id && it.steps[StepId("one")]?.status == StepStatus.WaitingApproval }.steps.getValue(StepId("one"))
            assertEquals(one.sessionId, again.sessionId)
            assertTrue(c.call(Api.Respond, RespondParams(again.sessionId!!, again.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.Deny()))))
            assertEquals(WorkflowRunStatus.Failed, await(c, w.id) { it.id == second.id && it.status != WorkflowRunStatus.Running }.status)
            withTimeout(5000) { while (notes().last() != "Run failed") delay(20) }
            assertEquals(listOf("Run started", "Denied: “First?”", "Run failed"), notes().takeLast(3))
            assertEquals(listOf(one.sessionId), server.harness.store.sessions(agent).map { it.id })
        }
    }

    @Test fun approvalAndReviewQuestionsAreFilledFromEarlierSteps() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "make"), WorkflowStep.Approval(StepId("ok"), "Reply to {{steps.a.output.who}}?"),
                WorkflowStep.Review(StepId("r"), "Note for {{input.team}}", "x"), edges = listOf(edge("a", "ok"), edge("ok", "r"))).copy(inputs = listOf(WorkflowInput("team"))))
            c.run(w.id, buildJsonObject { put("team", "Support") })
            val ok = await(c, w.id) { it.steps[StepId("ok")]?.status == StepStatus.WaitingApproval }.steps.getValue(StepId("ok"))
            withTimeout(5000) { while (server.harness.log.pending(ok.sessionId!!).isEmpty()) yield() }
            assertEquals("Reply to Bob?", (server.harness.log.pending(ok.sessionId!!).single() as PendingInteraction.Approval).reason)
            assertTrue(c.call(Api.Respond, RespondParams(ok.sessionId!!, ok.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))))
            val (_, asked) = waitingReview(server, c)
            assertEquals("Note for Support", asked.title)
            assertEquals("invalid_params", assertFailsWith<KinuvoException> { c.call(Api.UpdateWorkflow, w.copy(steps = listOf(WorkflowStep.Approval(StepId("ok"), "{{steps.zz.output}}")), edges = emptyList())) }.code)
        }
    }

    @Test fun savingWithoutChangingTheScheduleKeepsTheNextRun() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "echo")).copy(trigger = Trigger.Every(60)))
            val before = server.harness.store.workflows().next[w.id]
            delay(30)
            c.call(Api.UpdateWorkflow, w.copy(steps = listOf(tool("a", "echo").copy(position = Position(5f, 6f)))))
            assertEquals(before, server.harness.store.workflows().next[w.id])
            c.call(Api.UpdateWorkflow, w.copy(trigger = Trigger.Every(5)))
            assertNotEquals(before, server.harness.store.workflows().next[w.id])
        }
    }
}
