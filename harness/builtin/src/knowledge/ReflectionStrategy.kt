package kinuvo.harness.builtin.knowledge

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import kinuvo.harness.builtin.ToolLoop
import kinuvo.protocol.session.SessionEntry

internal fun reflectionStrategy(run: ReflectionStageRun, stage: String) = strategy<Unit, Unit>("kinuvoReflection$stage") {
    val review by reflectionStage(run, stage)
    edge(nodeStart forwardTo review)
    edge(review forwardTo nodeFinish)
}

private fun reflectionStage(run: ReflectionStageRun, stage: String) = subgraph<Unit, Unit>(stage) {
    var enabled = false
    val loop = ToolLoop(run.messages, run.response, { run.calls },
        toolName = { name -> name.takeIf { run.callable(name) } ?: "unknown" },
        repeated = { call -> SessionEntry.ToolResult(call.id, call.name, "This exact call already failed. Don't repeat it; try something else.", isError = true) },
        onResult = { if (it.isError) run.error(it.output) },
    )
    val prepare by node<Unit, Unit> {
        val initial = run.prepare(stage)
        enabled = initial != null
        if (initial != null) llm.writeSession { prompt = initial; tools = run.advertised }
    }
    edge(nodeStart forwardTo prepare)
    edge(prepare forwardTo loop.request onCondition { enabled })
    edge(prepare forwardTo nodeFinish onCondition { !enabled })
    loop.connect(this)
}
