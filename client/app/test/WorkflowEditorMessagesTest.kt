package kinuvo.app

import kinuvo.app.workflow.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.model.ProviderModelRef
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.workflow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkflowEditorMessagesTest {
    private val theme = EditorTheme(true, "#000", "#111", "#222", "#fff", "#999", "#333", "#06f", "#000", "#0a0", "#fa0", "#f00")
    private val workflow = Workflow(WorkflowId("w"), "W", listOf(
        WorkflowStep.AgentTurn(StepId("a"), AgentId("x"), "go", StepSession.Continue, Position(1f, 2f)),
        WorkflowStep.Model(StepId("m"), "sum {{steps.a.output}}", "brief", ProviderModelRef("p", "m1")),
        WorkflowStep.Tool(StepId("t"), "echo", buildJsonObject { put("n", 1); put("v", "{{steps.m.output.k}}") }),
        WorkflowStep.Approval(StepId("ok"), "Publish?"),
    ), listOf(WorkflowEdge(StepId("a"), StepId("m"))), agentId = AgentId("x"))

    @Test fun loadCarriesTheWorkflowCatalogThemeAndRun() {
        val run = WorkflowRun(WorkflowRunId("r"), workflow.id, WorkflowRunStatus.Running, 1, null, mapOf(StepId("a") to StepRun(StepStatus.Done, output = "out")), workflow)
        val catalog = EditorCatalog(listOf(EditorAgent(AgentId("x"), "X")), listOf(EditorModel("p", "m1", "M", "P")), listOf("echo"))
        val json = Json.parseToJsonElement(WorkflowEditorMessages.encode(EditorCommand.Load(workflow, catalog, theme, run))).jsonObject
        assertEquals("load", json.getValue("type").jsonPrimitive.content)
        assertEquals("x", json.getValue("workflow").jsonObject.getValue("agentId").jsonPrimitive.content)
        assertEquals("tool", json.getValue("workflow").jsonObject.getValue("steps").jsonArray[2].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("Done", json.getValue("run").jsonObject.getValue("steps").jsonObject.getValue("a").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("echo", json.getValue("catalog").jsonObject.getValue("tools").jsonArray.single().jsonPrimitive.content)
        assertEquals(true, json.getValue("theme").jsonObject.getValue("dark").jsonPrimitive.boolean)
    }

    @Test fun runWithoutARunOmitsIt() {
        val json = Json.parseToJsonElement(WorkflowEditorMessages.encode(EditorCommand.RunChanged(null))).jsonObject
        assertEquals("run", json.getValue("type").jsonPrimitive.content)
        assertNull(json["run"])
    }

    @Test fun changedRoundTripsAgentIdArgsAndIgnoresUnknownFields() {
        val encoded = WorkflowEditorMessages.encode(EditorCommand.Load(workflow, EditorCatalog(), theme)).let { Json.parseToJsonElement(it).jsonObject.getValue("workflow") }
        val raw = buildJsonObject {
            put("jsonrpc", "2.0"); put("type", "changed"); put("future", true)
            put("workflow", JsonObject(encoded.jsonObject + ("extra" to JsonPrimitive("kept by the page only"))))
        }.toString()
        val event = assertIs<EditorEvent.Changed>(WorkflowEditorMessages.decode(raw))
        assertEquals(workflow, event.workflow)
        assertEquals(AgentId("x"), event.workflow.agentId)
        assertEquals(JsonPrimitive(1), (event.workflow.steps[2] as WorkflowStep.Tool).args["n"])
    }

    @Test fun readyDecodesAndOtherMessagesAreIgnored() {
        assertEquals(EditorEvent.Ready, WorkflowEditorMessages.decode("""{"jsonrpc":"2.0","type":"ready"}"""))
        assertNull(WorkflowEditorMessages.decode("""{"jsonrpc":"2.0","method":"ui/notifications/size-changed","params":{"height":40}}"""))
        assertNull(WorkflowEditorMessages.decode("""{"jsonrpc":"2.0","type":"unknown"}"""))
        assertNull(WorkflowEditorMessages.decode("not json"))
    }

    @Test fun catalogListsEveryAgentAndModel() {
        val models = kinuvo.protocol.model.ModelsCatalog(listOf(kinuvo.protocol.model.ProviderModels("p", "Prov", listOf(kinuvo.protocol.model.ModelInfo("m1", "M one")))))
        val catalog = EditorCatalog.of(listOf(kinuvo.protocol.agent.AgentInfo(AgentId("x"), "X", "exec")), models, listOf("echo"))
        assertEquals(listOf(EditorModel("p", "m1", "M one", "Prov")), catalog.models)
        assertEquals(listOf(EditorAgent(AgentId("x"), "X")), catalog.agents)
    }

    @Test fun openSessionResolvesTheAgentTheServerUsed() {
        val steps = mapOf(StepId("a") to StepRun(StepStatus.Done, SessionId("s-a")), StepId("ok") to StepRun(StepStatus.WaitingApproval, SessionId("s-ok")),
            StepId("t") to StepRun(StepStatus.Done), StepId("r") to StepRun(StepStatus.WaitingApproval, SessionId("s-r")))
        val graph = workflow.copy(steps = workflow.steps + WorkflowStep.Review(StepId("r"), "Check"), agentId = AgentId("runner"))
        val run = WorkflowRun(WorkflowRunId("r1"), graph.id, WorkflowRunStatus.Running, 1, null, steps, graph)
        val state = WorkflowState(workflow = graph, draft = graph, activeRun = run)
        assertEquals(AgentId("x") to SessionId("s-a"), state.sessionOf(StepId("a")))
        assertEquals(AgentId("runner") to SessionId("s-ok"), state.sessionOf(StepId("ok")))
        assertEquals(AgentId("runner") to SessionId("s-r"), state.sessionOf(StepId("r")))
        assertNull(state.sessionOf(StepId("t")))
        val unset = graph.copy(agentId = null)
        assertEquals(AgentId("x") to SessionId("s-ok"), WorkflowState(draft = unset, activeRun = run.copy(workflow = unset)).sessionOf(StepId("ok")))
        assertNull(WorkflowState(draft = unset.copy(steps = unset.steps.drop(1)), activeRun = run.copy(workflow = unset.copy(steps = unset.steps.drop(1)))).sessionOf(StepId("ok")))
    }

    @Test fun reviewStepsAndTheirValueSurviveThePage() {
        val encoded = WorkflowEditorMessages.encode(EditorCommand.Load(workflow.copy(steps = listOf(WorkflowStep.Review(StepId("r"), "Ok?", "{{steps.a.output}}"))), EditorCatalog(), theme))
        val step = Json.parseToJsonElement(encoded).jsonObject.getValue("workflow").jsonObject.getValue("steps").jsonArray.single().jsonObject
        assertEquals("review", step.getValue("type").jsonPrimitive.content)
        assertEquals("{{steps.a.output}}", step.getValue("value").jsonPrimitive.content)
    }
}
