package kinuvo.testing.fixture

import kinuvo.gateways.agui.serveAgUi
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.workflow.*
import kinuvo.testing.server.FakeTool
import kinuvo.testing.server.FakeTools
import kinuvo.testing.server.Scenario
import kinuvo.testing.server.TestServer
import kinuvo.testing.server.showcase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DEFAULT_PORT = 18081

/** What the scripted model answers; Maestro flows assert it. */
const val FIXTURE_REPLY = "Hello from the fixture Server"

private val SCENARIOS: Map<String, suspend Scenario.() -> Unit> = mapOf(
    "chat" to {
        agent("e2e", "E2E Agent")
        withoutDefaultAgent()
        task("Plan mobile tests")
    },
    // Workflows to drive by hand or from Maestro: see [seedWorkflows].
    "workflows" to {
        agent("e2e", "E2E Agent")
        withoutDefaultAgent()
    },
    // Interrupts and Stop, triggered by the phrases in [main]'s script.
    "agui" to {
        agent("e2e", "E2E Agent")
        withoutDefaultAgent()
    },
    "showcase" to {
        showcase(agent("e2e", "E2E Agent"))
        withoutDefaultAgent()
    },
)

/** Tools the fixture Workflows call: a lookup that needs no Approval and a send that does. */
private val FIXTURE_TOOLS = FakeTools(
    FakeTool("orders_lookup") { """{"order":"A-10492","status":"delayed","days":3}""" },
    FakeTool("email_send", Risk.High, reason = "Sends an email to the customer") { "sent" },
    FakeTool("deploy", Risk.High, reason = "Pushes to production") { "deployed" },
    FakeTool("long_task") { delay(60_000); "done" },
)

/** A triage Workflow with every kind of step a model or a fake tool can serve, and a short one that only asks for an Approval. */
private suspend fun seedWorkflows(server: TestServer) {
    val agent = AgentId("e2e")
    fun edge(from: String, to: String, branch: Boolean? = null) = WorkflowEdge(StepId(from), StepId(to), branch)
    val client = server.client(token = server.auth.sessionToken()).also { it.connect() }
    client.call(Api.CreateWorkflow, Workflow(WorkflowId("triage"), "Triage a message", listOf(
        WorkflowStep.Model(StepId("model-1"), "Classify this message. Reply with urgent, customer and reason as JSON:\n\n{{input.message}}"),
        WorkflowStep.Tool(StepId("tool-1"), "orders_lookup", buildJsonObject { put("customer", "{{steps.model-1.output.customer}}") }),
        WorkflowStep.Condition(StepId("condition-1"), "{{steps.model-1.output.urgent}}", ConditionOp.Equals, "true"),
        WorkflowStep.Review(StepId("review-1"), "Check what was understood", "{{steps.model-1.output}}"),
        WorkflowStep.Approval(StepId("approval-1"), "Reply to {{steps.review-1.output.customer}} now?"),
        WorkflowStep.AgentTurn(StepId("agent-1"), agent, "Draft a reply to {{steps.review-1.output.customer}} about order {{steps.tool-1.output.order}}."),
        WorkflowStep.Tool(StepId("tool-2"), "email_send", buildJsonObject { put("body", "{{steps.agent-1.output}}") }),
        WorkflowStep.Tool(StepId("tool-3"), "orders_lookup", buildJsonObject { put("note", "not urgent") }),
    ), listOf(edge("model-1", "tool-1"), edge("tool-1", "condition-1"), edge("condition-1", "review-1", true), edge("review-1", "approval-1"),
        edge("approval-1", "agent-1"), edge("agent-1", "tool-2"), edge("condition-1", "tool-3", false)), agentId = agent,
        description = "Classifies a message, checks the order and replies when it is urgent.",
        inputs = listOf(WorkflowInput("message", "The message to triage", "The production database is down and customers cannot log in."))))
    client.call(Api.CreateWorkflow, Workflow(WorkflowId("publish"), "Publish with approval", listOf(
        WorkflowStep.Approval(StepId("approval-1"), "Publish the draft?"),
        WorkflowStep.Tool(StepId("tool-1"), "orders_lookup"),
    ), listOf(edge("approval-1", "tool-1")), agentId = agent))
    client.close()
}

/** Usage: `fixture-server [scenario] [--port=N] [--agui=PORT] [--agui-allow-origin=ORIGIN]...`; `--agui` also serves Agent `e2e` to AG-UI clients (browser Origins need `--agui-allow-origin`); the port defaults to `KINUVO_FIXTURE_PORT`, then 18081. */
fun main(args: Array<String>): Unit = runBlocking {
    val name = args.firstOrNull { !it.startsWith("--") } ?: "chat"
    val scenario = requireNotNull(SCENARIOS[name]) { "Unknown scenario '$name'; choose one of ${SCENARIOS.keys.joinToString()}" }
    val port = args.firstOrNull { it.startsWith("--port=") }?.substringAfter('=')?.toInt()
        ?: System.getenv("KINUVO_FIXTURE_PORT")?.toInt() ?: DEFAULT_PORT
    val server = TestServer.start(plugins = listOf(FIXTURE_TOOLS), script = {
        on("Classify this message") { reply("```json\n{\"urgent\":true,\"customer\":\"Dana Whitfield\",\"reason\":\"Production is down\"}\n```") }
        on("Draft a reply") { reply("Hi Dana, we are on it and will update you within the hour.") }
        on("Deploy") { toolCall("deploy"); reply("Deployed to production.") }
        on("Ask me") { toolCall("ask_user", """{"title":"Release details","questions":[{"id":"version","label":"Which version?"}]}"""); reply("Thanks, noted.") }
        on("Wait") { toolCall("long_task"); reply("Finished waiting.") }
        reply(FIXTURE_REPLY)
    }, scenario = scenario)
    if (name == "workflows") seedWorkflows(server)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    val url = server.listen(port)
    println("Fixture Server '$name' listening on $url")
    args.firstOrNull { it.startsWith("--agui=") }?.substringAfter('=')?.toInt()?.let { aguiPort ->
        val client = server.client(token = server.auth.sessionToken()).also { it.connect() }
        launch { serveAgUi(client, AgentId("e2e"), "127.0.0.1", aguiPort, args.filter { it.startsWith("--agui-allow-origin=") }.map { it.substringAfter('=') }.toSet()) }
        println("AG-UI gateway on http://127.0.0.1:$aguiPort/")
    }
    var shown = ""
    while (true) {
        if (server.pairingCode != shown) { shown = server.pairingCode; println("Pairing code: $shown") }
        delay(5_000)
    }
}
