package kinuvo.harness.core.knowledge

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import kinuvo.harness.core.ToolLoop
import kinuvo.protocol.SessionEntry

internal fun reflectionStrategy(run: ReflectionRun, stage: ReflectionStage) = strategy<Unit, Unit>("kinuvoReflection${stage.name}") {
    val review by reflectionStage(run, stage)
    edge(nodeStart forwardTo review)
    edge(review forwardTo nodeFinish)
}

private fun reflectionStage(run: ReflectionRun, stage: ReflectionStage) = subgraph<Unit, Unit>(stage.name) {
    var enabled = false
    val loop = ToolLoop(run.messages, run.response, { run.executor },
        toolName = { name -> name.takeIf { run.tools.any { it.descriptor.name == name } } ?: "unknown" },
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
