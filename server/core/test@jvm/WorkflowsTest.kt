package kinuvo.server

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.client.KinuvoClient
import kinuvo.client.KinuvoException
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.*
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.workflow.*
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileStore
import kinuvo.server.store.WorkflowData
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
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowsTest {
    private val agent = AgentId("work")
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
    )
    private suspend fun finished(c: KinuvoClient) = await(c, WorkflowId("test")) { it.status != WorkflowRunStatus.Running }
    private suspend fun client(server: TestServer) = server.client(token = server.auth.sessionToken()).also { it.connect() }
    private suspend fun await(client: KinuvoClient, id: WorkflowId, predicate: suspend (WorkflowRun) -> Boolean): WorkflowRun = withTimeout(10_000) {
        while (true) {
            client.call(Api.WorkflowRuns, id).firstOrNull { predicate(it) }?.let { return@withTimeout it }
            yield()
        }
        error("unreachable")
    }

    @Test fun linearOutputAndContinueSession() = runBlocking<Unit> {
        TestServer.start(script = { on("first") { reply("one") }; on("using one") { reply("two") } }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(turn("first"), turn("second", "using {{steps.first.output}}"), edges = listOf(edge("first", "second"))))
            c.call(Api.RunWorkflow, w.id)
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("one", done.steps[StepId("first")]?.output)
            assertEquals("two", done.steps[StepId("second")]?.output)
            assertTrue(server.prompts.inputs.any { it.text == "using one" })
            val second = w.steps[1] as WorkflowStep.AgentTurn
            c.call(Api.UpdateWorkflow, w.copy(steps = listOf(w.steps[0], second.copy(session = StepSession.Continue))))
            val started = c.call(Api.RunWorkflow, w.id)
            val again = await(c, w.id) { it.id == started.id && it.status != WorkflowRunStatus.Running }
            assertEquals(done.steps[second.id]?.sessionId, again.steps[second.id]?.sessionId)
        }
    }

    @Test fun branchesRunInParallelAndJoinWaitsForBoth() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(WorkflowStep.Approval(StepId("a"), "A"), WorkflowStep.Approval(StepId("b"), "B"),
                turn("join"), edges = listOf(edge("a", "join"), edge("b", "join"))))
            c.call(Api.RunWorkflow, w.id)
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
            c.call(Api.RunWorkflow, w.id)
            val waiting = await(c, w.id) { it.steps[StepId("approval")]?.status == StepStatus.WaitingApproval }
            val s = waiting.steps.getValue(StepId("approval"))
            withTimeout(5000) { while (server.harness.log.pending(s.sessionId!!).isEmpty()) yield() }
            assertTrue(c.call(Api.Respond, RespondParams(s.sessionId!!, s.interactionId!!, InteractionAnswer.Approval(ApprovalDecision.Deny()))))
            val failed = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Failed, failed.status)
            assertEquals(StepStatus.Skipped, failed.steps[StepId("after")]?.status)
            c.call(Api.RunWorkflow, w.id)
            val next = await(c, w.id) { it.id != waiting.id && it.steps[StepId("approval")]?.status == StepStatus.WaitingApproval }
            assertEquals(WorkflowRunStatus.Cancelled, c.call(Api.CancelWorkflow, next.id).status)
            assertTrue(server.harness.log.pending(next.steps.getValue(StepId("approval")).sessionId!!).isEmpty())
        }
    }

    @Test fun cancellingAgentTurnStopsItsSession() = runBlocking<Unit> {
        TestServer.start(script = { on("wait") { toolCall("ask_user", """{"questions":[{"id":"ok","label":"Continue?","kind":"text"}]}"""); reply("later") } }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(turn("wait")))
            c.call(Api.RunWorkflow, w.id)
            val waiting = await(c, w.id) { r -> r.steps[StepId("wait")]?.sessionId?.let { server.harness.log.pending(it).isNotEmpty() } == true }
            val cancelled = c.call(Api.CancelWorkflow, waiting.id)
            assertEquals(WorkflowRunStatus.Cancelled, cancelled.status)
            assertEquals(StepStatus.Cancelled, cancelled.steps[StepId("wait")]?.status)
            assertTrue(server.harness.log.pending(cancelled.steps.getValue(StepId("wait")).sessionId!!).isEmpty())
        }
    }

    @Test fun recoveryPreservesDoneOutputsAndCancelsInterruptedSteps() = runBlocking<Unit> {
        val home = Files.createTempDirectory("workflows-recovery").toFile()
        val w = workflow(turn("first"), WorkflowStep.Approval(StepId("approve"), "Wait"), edges = listOf(edge("first", "approve")))
        try {
            TestServer.start(home = Path(home.path), scenario = {
                agent("work")
                FileStore(AgentFiles(Path(home.path))).saveWorkflows(WorkflowData(listOf(w), listOf(WorkflowRun(WorkflowRunId("interrupted"), w.id, WorkflowRunStatus.Running, 1,
                    steps = mapOf(StepId("first") to StepRun(StepStatus.Done, output = "retained"), StepId("approve") to StepRun(StepStatus.WaitingApproval)), workflow = w))))
            }).use { server ->
                val recovered = client(server).call(Api.WorkflowRuns, w.id).single()
                assertEquals(WorkflowRunStatus.Cancelled, recovered.status)
                assertEquals("retained", recovered.steps[StepId("first")]?.output)
                assertEquals(StepStatus.Cancelled, recovered.steps[StepId("approve")]?.status)
                assertNotNull(recovered.endedAt)
            }
        } finally { home.deleteRecursively() }
    }

    @Test fun cyclesUnknownEdgesAndInvalidReferencesAreTypedErrors() = runBlocking<Unit> {
        TestServer.start(scenario = { agent("work") }).use { server ->
            val c = client(server)
            for (w in listOf(workflow(turn("a"), edges = listOf(edge("a", "a"))),
                workflow(turn("a"), edges = listOf(edge("a", "missing"))), workflow(turn("a", "{{steps.missing.output}}")))) {
                val error = assertFailsWith<KinuvoException> { c.call(Api.CreateWorkflow, w) }
                assertEquals("invalid_params", error.code)
            }
        }
    }

    @Test fun toolStepRunsWithoutASession() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.call(Api.RunWorkflow, c.call(Api.CreateWorkflow, workflow(tool("a", "echo", buildJsonObject { put("x", 1) }))).id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("""{"x":1}""", done.steps[StepId("a")]?.output)
            assertNull(done.steps[StepId("a")]?.sessionId)
            assertTrue(server.harness.store.sessions(agent).isEmpty())
        }
    }

    @Test fun failingToolFailsStepAndRun() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.call(Api.RunWorkflow, c.call(Api.CreateWorkflow, workflow(tool("a", "boom"), tool("b", "echo"), edges = listOf(edge("a", "b")))).id)
            val failed = finished(c)
            assertEquals(WorkflowRunStatus.Failed, failed.status)
            assertEquals(StepStatus.Failed, failed.steps[StepId("a")]?.status)
            assertTrue(failed.steps[StepId("a")]?.error.orEmpty().contains("kaput"))
            assertEquals(StepStatus.Skipped, failed.steps[StepId("b")]?.status)
            c.call(Api.UpdateWorkflow, workflow(tool("a", "missing_tool")))
            c.call(Api.RunWorkflow, WorkflowId("test"))
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
            c.call(Api.RunWorkflow, c.call(Api.CreateWorkflow, workflow(tool("a", "make"), tool("b", "echo", args), edges = listOf(edge("a", "b")))).id)
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
            c.call(Api.RunWorkflow, c.call(Api.CreateWorkflow, workflow(tool("a", "make"),
                tool("b", "echo", buildJsonObject { put("x", "{{steps.a.output.items.9.name}}") }), edges = listOf(edge("a", "b")))).id)
            val failed = finished(c)
            assertEquals(WorkflowRunStatus.Failed, failed.status)
            assertTrue(failed.steps[StepId("b")]?.error.orEmpty().contains("{{steps.a.output.items.9.name}}"))
        }
    }

    @Test fun modelStepRepliesWithoutASession() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), script = { on("hello Bob") { reply("greeted") } }, scenario = { agent("work") }).use { server ->
            val c = client(server)
            c.call(Api.RunWorkflow, c.call(Api.CreateWorkflow, workflow(tool("a", "make"),
                WorkflowStep.Model(StepId("m"), "hello {{steps.a.output.who}}", system = "Be brief for {{steps.a.output.count}}"), edges = listOf(edge("a", "m")))).id)
            val done = finished(c)
            assertEquals(WorkflowRunStatus.Done, done.status)
            assertEquals("greeted", done.steps[StepId("m")]?.output)
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
                val started = c.call(Api.RunWorkflow, w.id)
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

    @Test fun cancellingAToolWaitingForApprovalResolvesIt() = runBlocking<Unit> {
        TestServer.start(plugins = listOf(fakes), scenario = { agent("work") }).use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, workflow(tool("a", "deploy")))
            c.call(Api.RunWorkflow, w.id)
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
}
