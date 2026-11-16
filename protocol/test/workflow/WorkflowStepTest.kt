package kinuvo.protocol.workflow

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.model.ProviderModelRef
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowStepTest {
    @Test fun toolAndModelStepsRoundTrip() {
        val workflow = Workflow(WorkflowId("w"), "W", listOf(
            WorkflowStep.Tool(StepId("t"), "echo", buildJsonObject { put("a", "{{steps.x.output.a}}") }, Position(1f, 2f)),
            WorkflowStep.Model(StepId("m"), "summarise", "be brief", ProviderModelRef("p", "m1")),
            WorkflowStep.Review(StepId("r"), "Check the draft", "{{steps.m.output}}", Position(3f, 4f)),
        ), agentId = AgentId("work"))
        val text = ProtocolJson.encodeToString(workflow)
        assertEquals(workflow, ProtocolJson.decodeFromString<Workflow>(text))
    }

    @Test fun reviewStepUsesItsOwnTypeAndDefaultsToNoValue() {
        val step = ProtocolJson.decodeFromString<WorkflowStep>("""{"type":"review","id":"r","text":"Ok?"}""")
        assertEquals(WorkflowStep.Review(StepId("r"), "Ok?"), step)
    }

    @Test fun storedWorkflowsWithoutRunsAsAgentStayValid() {
        val old = """{"id":"w","name":"W","steps":[{"type":"approval","id":"a","text":"ok"}]}"""
        assertNull(ProtocolJson.decodeFromString<Workflow>(old).agentId)
    }
}
