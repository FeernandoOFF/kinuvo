package kinuvo.plugins.executor

import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.QuestionKind
import kinuvo.protocol.approval.RespondParams
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.workflow.StepId
import kinuvo.protocol.workflow.StepRun
import kinuvo.protocol.workflow.StepStatus
import kinuvo.protocol.workflow.Workflow
import kinuvo.protocol.workflow.WorkflowId
import kinuvo.protocol.workflow.WorkflowRun
import kinuvo.protocol.workflow.WorkflowRunStatus
import kinuvo.testing.server.FakeTool
import kinuvo.testing.server.FakeTools
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the Workflows in `docs/examples/workflows`; network tools are fakes that answer in the shape the real ones do. */
class WorkflowExamplesTest {
    private val directory = File("../../docs/examples/workflows")
    private fun load(name: String) = ProtocolJson.decodeFromString<Workflow>(File(directory, "$name.json").readText())

    private val hackerNews = """{"hits":[
        {"title":"Kotlin 3 released","url":"https://example.com/kotlin","points":412,"objectID":"1","author":"a","_tags":["story"]},
        {"title":"Ask HN: What are you building?","url":null,"points":120,"objectID":"2","author":"b","_tags":["ask_hn"]}],"nbHits":2}"""
    private val encyclopedia = """{"title":"Kotlin (programming language)","extract":"Kotlin is a statically typed language."}"""

    private val fakes = FakeTools(FakeTool(ToolNames.WEB_FETCH) { args ->
        "HTTP 200\n\n" + if ("wikipedia" in args["url"]?.jsonPrimitive?.content.orEmpty()) encyclopedia else hackerNews
    })

    private suspend fun server(script: kinuvo.testing.harness.ModelScript.() -> Unit) = TestServer.start(plugins = listOf(ExecutorPlugin(), fakes), script = script, scenario = {
        withoutDefaultAgent()
        agent("default") { it.copy(tools = listOf(ToolNames.RUN_CODE)) }
    })

    private suspend fun client(server: TestServer) = server.client(token = server.auth.sessionToken()).also { it.connect() }

    private suspend fun await(c: KinuvoClient, id: WorkflowId, predicate: (WorkflowRun) -> Boolean): WorkflowRun = withTimeout(20_000) {
        while (true) {
            c.call(Api.WorkflowRuns, id).firstOrNull(predicate)?.let { return@withTimeout it }
            delay(50)
        }
        error("unreachable")
    }

    private suspend fun waiting(server: TestServer, c: KinuvoClient, id: WorkflowId, step: String): Pair<StepRun, PendingInteraction> {
        val s = await(c, id) { it.steps[StepId(step)]?.status == StepStatus.WaitingApproval }.steps.getValue(StepId(step))
        withTimeout(5_000) { while (server.harness.log.pending(s.sessionId!!).isEmpty()) yield() }
        return s to server.harness.log.pending(s.sessionId!!).single()
    }

    private suspend fun respond(c: KinuvoClient, step: StepRun, answer: InteractionAnswer) =
        assertTrue(c.call(Api.Respond, RespondParams(step.sessionId!!, step.interactionId!!, answer)))

    private fun WorkflowRun.output(step: String) = steps.getValue(StepId(step)).output.orEmpty()

    @Test fun everyExampleDecodesAndPassesSaveValidation() = runBlocking<Unit> {
        val files = directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue(files.size >= 3, "No examples found in ${directory.absolutePath}")
        server {}.use { server ->
            val c = client(server)
            for (file in files) {
                val workflow = load(file.nameWithoutExtension)
                assertEquals(file.nameWithoutExtension, workflow.id.value)
                assertTrue(workflow.steps.all { it.position == null })
                assertEquals(workflow.id, c.call(Api.CreateWorkflow, workflow).id, file.name)
            }
        }
    }

    @Test fun extractReviewFillRunsWithTheRealScriptTool() = runBlocking<Unit> {
        val extracted = """{"customer":"Dana Whitfield","order":"A-10492","issue":"The screen arrived cracked.","refund":249.9,"urgent":true}"""
        server { on("extract customer") { reply(extracted) } }.use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, load("extract-review-fill"))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val (step, asked) = waiting(server, c, w.id, "review-1")
            asked as PendingInteraction.Questions
            assertEquals(listOf("customer", "order", "issue", "refund", "urgent"), asked.questions.map { it.id })
            assertEquals(listOf(QuestionKind.Text, QuestionKind.Text, QuestionKind.Text, QuestionKind.Number, QuestionKind.YesNo), asked.questions.map { it.kind })
            val sample = await(c, w.id) { true }
            assertContains(sample.output("tool-1"), "Dana Whitfield")
            respond(c, step, InteractionAnswer.Questions(asked.questions.associate { it.id to it.default }.plus("customer" to listOf("Dana Whitfield-Smith")).plus("urgent" to listOf("No"))))
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status, done.steps.toString())
            assertEquals(249.9, Json.parseToJsonElement(done.output("review-1")).jsonObject.getValue("refund").jsonPrimitive.content.toDouble())
            val letter = done.output("tool-2")
            assertContains(letter, "Hi Dana Whitfield-Smith,")
            assertContains(letter, "order A-10492")
            assertContains(letter, "refunded 249.9 ")
            assertTrue("urgent" !in letter)
        }
    }

    @Test fun frontPageDigestReducesTheFetchWithoutTokens() = runBlocking<Unit> {
        server { on("Write a digest") { reply("Kotlin 3 leads the front page.") }; on("reviewed Hacker News digest") { reply("Shared it.") } }.use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, load("front-page-digest"))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val (step, asked) = waiting(server, c, w.id, "review-1")
            assertEquals(listOf("Kotlin 3 leads the front page."), (asked as PendingInteraction.Questions).questions.single().default)
            respond(c, step, InteractionAnswer.Questions(mapOf(asked.questions.single().id to listOf("Edited digest."))))
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status, done.steps.toString())
            val entries = Json.parseToJsonElement(done.output("tool-2")).jsonArray
            assertEquals(listOf("Kotlin 3 released", "Ask HN: What are you building?"), entries.map { it.jsonObject.getValue("title").jsonPrimitive.content })
            assertEquals("https://news.ycombinator.com/item?id=2", entries[1].jsonObject.getValue("url").jsonPrimitive.content)
            assertEquals("Edited digest.", done.output("review-1"))
            assertTrue(server.prompts.inputs.any { "Edited digest." in it.text })
            assertEquals("Shared it.", done.output("agent-1"))
        }
    }

    @Test fun twoSourcesRunInParallelThenWaitForApproval() = runBlocking<Unit> {
        server { on("Write a briefing") { reply("Kotlin is typed. People discuss Kotlin 3.") }; on("Share this briefing") { reply("Shared it.") } }.use { server ->
            val c = client(server)
            val w = c.call(Api.CreateWorkflow, load("two-sources-one-answer"))
            c.call(Api.RunWorkflow, RunWorkflowParams(w.id))
            val (step, _) = waiting(server, c, w.id, "approval-1")
            val before = await(c, w.id) { true }
            assertEquals(StepStatus.Done, before.steps.getValue(StepId("tool-1")).status)
            assertEquals(StepStatus.Done, before.steps.getValue(StepId("tool-2")).status)
            assertEquals(StepStatus.Pending, before.steps.getValue(StepId("agent-1")).status)
            respond(c, step, InteractionAnswer.Approval(ApprovalDecision.AllowOnce))
            val done = await(c, w.id) { it.status != WorkflowRunStatus.Running }
            assertEquals(WorkflowRunStatus.Done, done.status, done.steps.toString())
            assertContains(done.output("tool-1"), "statically typed")
            assertContains(done.output("tool-2"), "Kotlin 3 released")
            assertEquals("Shared it.", done.output("agent-1"))
        }
    }
}
