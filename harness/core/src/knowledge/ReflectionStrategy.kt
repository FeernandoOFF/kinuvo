package kinuvo.harness.core.knowledge

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import kinuvo.harness.core.ModelMeasurement
import kinuvo.harness.core.TokenUsageCapture
import kinuvo.harness.core.canonical
import kinuvo.harness.core.toolCall
import kinuvo.protocol.RequestKind
import kinuvo.protocol.ReflectionResult
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

internal fun reflectionStrategy(run: ReflectionRun) = strategy<Unit, ReflectionResult>("kinuvoReflection") {
    val gather by node<Unit, Unit> { run.gather() }
    val memory by reflectionStage(run, ReflectionStage.Memory)
    val knowledge by reflectionStage(run, ReflectionStage.Knowledge)
    val skills by reflectionStage(run, ReflectionStage.Skills)
    val finish by node<Unit, ReflectionResult> { run.result() }
    edge(nodeStart forwardTo gather)
    edge(gather forwardTo memory)
    edge(memory forwardTo knowledge)
    edge(knowledge forwardTo skills)
    edge(skills forwardTo finish)
    edge(finish forwardTo nodeFinish)
}

private fun reflectionStage(run: ReflectionRun, stage: ReflectionStage) = subgraph<Unit, Unit>(stage.name) {
    var enabled = false
    var pending = emptyList<SessionEntry.ToolCall>()
    val failed = mutableSetOf<Pair<String, String>>()
    var repeats = 0
    val prepare by node<Unit, Unit> {
        val initial = run.prepare(stage)
        enabled = initial != null
        if (initial != null) llm.writeSession { prompt = initial; tools = run.advertised }
    }
    val request by node<Unit, Unit>("streamResponse") {
        check(repeats < 2) { "Stopped: the model kept repeating a tool call that failed" }
        val response = mutableListOf<SessionEntry>()
        val text = StringBuilder()
        withContext(TokenUsageCapture()) {
            llm.writeSession {
                requestLLMStreaming().requireEndFrame().collect { frame -> when (frame) {
                    is StreamFrame.TextDelta -> text.append(frame.text)
                    is StreamFrame.ReasoningComplete -> response += SessionEntry.Reasoning(
                        (frame.summary?.takeIf { it.isNotEmpty() } ?: frame.content).joinToString("\n\n"), frame.id, frame.encrypted)
                    is StreamFrame.ToolCallComplete -> response += SessionEntry.ToolCall(frame.id ?: Uuid.random().toString(), frame.name, frame.content)
                    else -> Unit
                } }
                pending = response.filterIsInstance<SessionEntry.ToolCall>()
                prompt = prompt.withMessages { it + run.messages.messages(response.filterIsInstance<SessionEntry.Reasoning>() +
                    listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { SessionEntry.AssistantMessage(it.toString()) }) + pending) }
            }
        }
    }
    val executeTool by node<Unit, Unit>("executeTool") {
        check(repeats < 2) { "Stopped: the model kept repeating a tool call that failed" }
        val call = pending.first()
        val key = call.name to canonical(call.args)
        val result = toolCall(call, call.name.takeIf { name -> run.tools.any { it.descriptor.name == name } } ?: "unknown") {
            if (key in failed) {
                repeats++
                SessionEntry.ToolResult(call.id, call.name, "This exact call already failed. Don't repeat it; try something else.", isError = true)
            } else run.executor.call(call)
        }
        if (result.isError) { failed += key; run.error(result.output) }
        llm.writeSession { prompt = prompt.withMessages { it + run.messages.messages(listOf(result)) } }
        pending = pending.drop(1)
    }
    edge(nodeStart forwardTo prepare)
    edge(prepare forwardTo request onCondition { enabled })
    edge(prepare forwardTo nodeFinish onCondition { !enabled })
    edge(request forwardTo nodeFinish onCondition { pending.isEmpty() })
    edge(request forwardTo executeTool onCondition { pending.isNotEmpty() })
    edge(executeTool forwardTo executeTool onCondition { pending.isNotEmpty() })
    edge(executeTool forwardTo request onCondition { pending.isEmpty() })
}

internal class ReflectionFeature private constructor() {
    class Config : FeatureConfig() {
        internal lateinit var run: ReflectionRun
    }

    companion object : AIAgentGraphFeature<Config, ReflectionFeature> {
        override val key = createStorageKey<ReflectionFeature>("kinuvo-reflection")
        override fun createInitialConfig(agentConfig: AIAgentConfig) = Config()

        @OptIn(InternalAgentsApi::class)
        override fun install(config: Config, pipeline: AIAgentGraphPipeline): ReflectionFeature {
            val run = config.run
            var measurement: ModelMeasurement? = null
            var end: StreamFrame.End? = null
            pipeline.interceptLLMStreamingStarting(this) {
                end = null
                measurement = ModelMeasurement(run.measuredAgent.config, RequestKind.Reflection, requireNotNull(currentCoroutineContext()[TokenUsageCapture]))
                pipeline.onLLMCallStarting(it.eventId, it.executionInfo, it.context, it.runId, it.prompt, it.model, it.tools)
            }
            pipeline.interceptLLMStreamingFrameReceived(this) {
                measurement?.frame(it.streamFrame)
                (it.streamFrame as? StreamFrame.End)?.let { frame -> end = frame }
            }
            pipeline.interceptLLMStreamingCompleted(this) {
                measurement?.let { current -> run.measurements += current.finish(end != null) }
                measurement = null
                if (end != null) pipeline.onLLMCallCompleted(it.eventId, it.executionInfo, it.context, it.runId, it.prompt, it.model, it.tools, Message.Assistant("", end!!.metaInfo))
                else pipeline.onLLMCallFailed(it.eventId, it.executionInfo, it.context, it.runId, it.prompt, it.model, it.tools, IllegalStateException("Stream interrupted"))
            }
            pipeline.interceptLLMStreamingFailed(this) {
                measurement?.let { current -> run.measurements += current.finish(false) }
                measurement = null
                pipeline.onLLMCallFailed(it.eventId, it.executionInfo, it.context, it.runId, it.prompt, it.model, run.advertised, it.error)
            }
            return ReflectionFeature()
        }
    }
}
