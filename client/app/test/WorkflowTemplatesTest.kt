package kinuvo.app

import kinuvo.app.workflow.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.workflow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkflowTemplatesTest {
    private val agent = AgentId("x")

    @Test fun runInputTypesTextThatReadsAsJsonAndFallsBackToDefaults() {
        val inputs = listOf(WorkflowInput("name", default = "Dana"), WorkflowInput("count", default = "3"), WorkflowInput("urgent"), WorkflowInput("tags"), WorkflowInput("quoted"))
        val input = runInput(inputs, mapOf("urgent" to "true", "tags" to "[\"a\"]", "quoted" to "\"yes\""))
        assertEquals(JsonPrimitive("Dana"), input["name"])
        assertEquals(JsonPrimitive(3), input["count"])
        assertEquals(JsonPrimitive(true), input["urgent"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), input["tags"])
        assertEquals(JsonPrimitive("\"yes\""), input["quoted"])
    }

    @Test fun onlyAnUntouchedNewWorkflowIsBlank() {
        val fresh = Workflow(WorkflowId("w"), NEW_WORKFLOW, listOf(WorkflowStep.AgentTurn(StepId("agent-1"), agent, "")))
        assertTrue(fresh.blank)
        assertFalse(fresh.copy(steps = listOf(WorkflowStep.AgentTurn(StepId("agent-1"), agent, "go"))).blank)
        assertFalse(WorkflowTemplates.all.first().build(fresh.id, agent).blank)
    }

    @Test fun templatesAreGraphsTheServerAccepts() {
        val reference = Regex("\\{\\{(steps\\.([a-zA-Z0-9_-]+)\\.output|input\\.([a-zA-Z0-9_-]+))[^}]*}}")
        for (template in WorkflowTemplates.all) {
            val w = template.build(WorkflowId("w"), agent)
            val ids = w.steps.map { it.id }
            assertEquals(ids.distinct(), ids, template.name)
            assertEquals(agent, w.agentId)
            assertTrue(w.edges.all { it.from in ids && it.to in ids }, template.name)
            assertTrue(w.edges.all { (it.branch != null) == (w.steps.first { s -> s.id == it.from } is WorkflowStep.Condition) }, template.name)
            fun ancestors(id: StepId): Set<StepId> = w.edges.filter { it.to == id }.flatMap { ancestors(it.from) + it.from }.toSet()
            for (step in w.steps) {
                val text = Json.encodeToString(WorkflowStep.serializer(), step)
                for (match in reference.findAll(text)) {
                    val (_, from, input) = match.destructured
                    if (from.isNotEmpty()) assertContains(ancestors(step.id), StepId(from), "${template.name}: ${step.id}")
                    else assertContains(w.inputs.map { it.name }, input, "${template.name}: ${step.id}")
                }
            }
        }
    }
}
