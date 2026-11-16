package kinuvo.app

import kinuvo.app.workflow.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.workflow.*
import kotlin.test.*

class WorkflowEditsTest {
    private val agents = listOf(AgentInfo(AgentId("a"), "A", "exec"))
    private val one = WorkflowStep.AgentTurn(StepId("step-1"), AgentId("a"), "")
    private val flow = Workflow(WorkflowId("w"), "W", listOf(one))

    @Test fun addPicksTheFirstFreeIdAndConnectsAfterTheAnchor() {
        val (withTwo, two) = flow.add(StepKind.Agent, agents, one)
        assertEquals(StepId("step-2"), two)
        assertEquals(listOf(WorkflowEdge(one.id, two)), withTwo.edges)
        val (withApproval, approval) = withTwo.add(StepKind.Approval, agents, null)
        assertEquals(StepId("approval-1"), approval)
        assertEquals(withTwo.edges, withApproval.edges)
        assertEquals(StepId("step-3"), withApproval.add(StepKind.Agent, agents, null).second)
    }

    @Test fun addPlacesNewStepsToTheRightOfTheAnchor() {
        val (graph, id) = flow.add(StepKind.Agent, agents, one)
        val positions = layout(graph)
        assertEquals(positions.getValue(one.id).x + ColumnGap, positions.getValue(id).x)
    }

    @Test fun removeDropsTheStepAndItsEdges() {
        val (graph, two) = flow.add(StepKind.Agent, agents, one)
        val removed = graph.remove(two)
        assertEquals(listOf(one.id), removed.steps.map { it.id })
        assertTrue(removed.edges.isEmpty())
    }

    @Test fun ancestorsAndDescendantsFollowEdges() {
        val (a, two) = flow.add(StepKind.Agent, agents, one)
        val (graph, three) = a.add(StepKind.Agent, agents, a.steps.first { it.id == two })
        assertEquals(listOf(one.id, two), ancestors(graph, three))
        assertEquals(setOf(two, three), descendants(graph, one.id))
    }

    @Test fun moveSetsOnlyThatStepsPosition() {
        val moved = flow.move(one.id, Position(5f, 6f))
        assertEquals(Position(5f, 6f), moved.steps.single().position)
    }
}
