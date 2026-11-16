package kinuvo.app.workflow

import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.workflow.*

enum class StepKind { Agent, Approval }

const val ColumnGap = 280f
const val RowGap = 104f

fun Workflow.add(kind: StepKind, agents: List<AgentInfo>, after: WorkflowStep?): Pair<Workflow, StepId> {
    val taken = steps.map { it.id.value }.toSet()
    val prefix = if (kind == StepKind.Approval) "approval" else "step"
    val id = StepId(generateSequence(1) { it + 1 }.map { "$prefix-$it" }.first { it !in taken })
    val positions = layout(this)
    val anchor = after?.let { positions[it.id] }
    val position = anchor?.let { a ->
        val x = a.x + ColumnGap
        val used = positions.values.filter { kotlin.math.abs(it.x - x) < 1f }.maxOfOrNull { it.y }
        Position(x, if (used == null) a.y else used + RowGap)
    } ?: Position((positions.values.maxOfOrNull { it.x } ?: -ColumnGap) + ColumnGap, 0f)
    val step = when (kind) {
        StepKind.Agent -> WorkflowStep.AgentTurn(id, (after as? WorkflowStep.AgentTurn)?.agentId ?: agents.first().id, "", position = position)
        StepKind.Approval -> WorkflowStep.Approval(id, "", position)
    }
    return copy(steps = steps + step, edges = edges + listOfNotNull(after?.let { WorkflowEdge(it.id, id) })) to id
}

fun Workflow.remove(id: StepId) = copy(steps = steps.filterNot { it.id == id }, edges = edges.filterNot { it.from == id || it.to == id })

fun Workflow.move(id: StepId, to: Position) = copy(steps = steps.map {
    if (it.id != id) it else when (it) {
        is WorkflowStep.AgentTurn -> it.copy(position = to)
        is WorkflowStep.Approval -> it.copy(position = to)
    }
})

/** Each step's saved position, or its column by longest path from a root and its row within that column. */
fun layout(workflow: Workflow): Map<StepId, Position> {
    val depth = mutableMapOf<StepId, Int>()
    fun depthOf(id: StepId, seen: Set<StepId> = emptySet()): Int = depth[id] ?: (workflow.edges.filter { it.to == id && it.from !in seen }
        .maxOfOrNull { depthOf(it.from, seen + id) + 1 } ?: 0).also { depth[id] = it }
    val rows = mutableMapOf<Int, Int>()
    return workflow.steps.associate { step ->
        step.id to (step.position ?: depthOf(step.id).let { column ->
            val row = rows.getOrElse(column) { 0 }.also { rows[column] = it + 1 }
            Position(column * ColumnGap, row * RowGap)
        })
    }
}

fun ancestors(workflow: Workflow, id: StepId): List<StepId> {
    val found = linkedSetOf<StepId>()
    fun visit(at: StepId) { workflow.edges.filter { it.to == at }.forEach { if (found.add(it.from)) visit(it.from) } }
    visit(id)
    return workflow.steps.map { it.id }.filter { it in found }
}

fun descendants(workflow: Workflow, id: StepId): Set<StepId> {
    val found = mutableSetOf<StepId>()
    fun visit(at: StepId) { workflow.edges.filter { it.from == at }.forEach { if (found.add(it.to)) visit(it.to) } }
    visit(id)
    return found
}
