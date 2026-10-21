@file:OptIn(ai.koog.agents.core.annotation.InternalAgentsApi::class)

package kinuvo.harness.core

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.serialization.JSONObject
import kinuvo.harness.core.tools.ToolExecutor
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ToolIdentity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

internal class StreamResponse(private val identity: (String) -> ToolIdentity? = { null }) {
    var messageId: String? = null
    val text = StringBuilder()
    private val entries = mutableListOf<SessionEntry>()
    val reasoning get() = entries.filterIsInstance<SessionEntry.Reasoning>()
    val calls get() = entries.filterIsInstance<SessionEntry.ToolCall>()
    var end: StreamFrame.End? = null
        private set
    var failure: Throwable? = null
    val success get() = end != null && failure == null

    fun reset() { text.clear(); entries.clear(); end = null; failure = null }

    fun frame(frame: StreamFrame) {
        when (frame) {
            is StreamFrame.TextDelta -> text.append(frame.text)
            is StreamFrame.ReasoningComplete -> entries += SessionEntry.Reasoning(
                (frame.summary?.takeIf { it.isNotEmpty() } ?: frame.content).joinToString("\n\n"), frame.id, frame.encrypted, messageId)
            is StreamFrame.ToolCallComplete -> entries += SessionEntry.ToolCall(
                frame.id ?: Uuid.random().toString(), frame.name, frame.content, identity(frame.name))
            is StreamFrame.End -> end = frame
            else -> Unit
        }
    }

    fun messages(messages: SessionMessages) = messages.messages(reasoning +
        listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { SessionEntry.AssistantMessage(it.toString(), messageId) }) + calls)
}

/** Streaming and sequential controlled tool execution, wired into the caller's preparation nodes. */
internal class ToolLoop(
    private val messages: SessionMessages,
    private val response: StreamResponse,
    private val executor: () -> ToolExecutor,
    private val toolName: (String) -> String,
    private val repeated: suspend (SessionEntry.ToolCall) -> SessionEntry.ToolResult,
    private val onResponse: suspend () -> Unit = {},
    private val onResult: suspend (SessionEntry.ToolResult) -> Unit = {},
    private val onFrame: suspend (StreamFrame) -> Unit = {},
    private val stopped: (String) -> Unit = {},
) {
    private val failed = mutableSetOf<Pair<String, String>>()
    private var repeats = 0
    private var pending = emptyList<SessionEntry.ToolCall>()

    fun checkRepeats() = check(repeats < 2) { "Stopped: the model kept repeating a tool call that failed" }

    val request by node<Unit, Unit>("streamResponse") {
        checkRepeats()
        llm.writeSession {
            requestLLMStreaming().requireEndFrame().collect { onFrame(it) }
            prompt = prompt.withMessages { it + response.messages(messages) }
        }
        onResponse()
        pending = response.calls
    }

    private val executeTool by node<Unit, Unit>("executeTool") {
        checkRepeats()
        val call = pending.first()
        val key = call.name to canonical(call.args)
        val result = toolCall(call, toolName(call.name)) {
            if (key in failed) { repeats++; repeated(call) }
            else executor().call(call, stopped = stopped)
        }
        if (result.isError) failed += key
        onResult(result)
        llm.writeSession { prompt = prompt.withMessages { it + messages.messages(listOf(result)) } }
        pending = pending.drop(1)
    }

    fun connect(builder: AIAgentSubgraphBuilderBase<Unit, Unit>, beforeRequest: AIAgentNodeBase<Unit, Unit> = request, finish: AIAgentNodeBase<Unit, *> = builder.nodeFinish) = with(builder) {
        edge(request forwardTo finish onCondition { pending.isEmpty() })
        edge(request forwardTo executeTool onCondition { pending.isNotEmpty() })
        edge(executeTool forwardTo executeTool onCondition { pending.isNotEmpty() })
        edge(executeTool forwardTo beforeRequest onCondition { pending.isEmpty() })
    }
}

internal class ToolLoopFeature private constructor() {
    class Config : FeatureConfig() {
        lateinit var requests: ModelRequests
        lateinit var response: StreamResponse
        var starting: suspend () -> Unit = {}
        var saveResponse: suspend () -> Unit = {}
    }

    companion object : AIAgentGraphFeature<Config, ToolLoopFeature> {
        override val key = createStorageKey<ToolLoopFeature>("kinuvo-tool-loop")
        override fun createInitialConfig(agentConfig: AIAgentConfig) = Config()

        override fun install(config: Config, pipeline: AIAgentGraphPipeline): ToolLoopFeature {
            val response = config.response
            val requests = config.requests
            pipeline.interceptLLMCallStarting(this) { requests.start(); config.starting() }
            pipeline.interceptLLMCallCompleted(this) {
                it.response?.let { response -> requests.metadata(response) }
                requests.finish(true)
            }
            pipeline.interceptLLMCallFailed(this) { requests.finish(false) }
            pipeline.interceptLLMStreamingStarting(this) {
                response.reset()
                pipeline.onLLMCallStarting(it.eventId, it.executionInfo, it.context, it.runId, it.prompt, it.model, it.tools)
            }
            pipeline.interceptLLMStreamingFrameReceived(this) { requests.frame(it.streamFrame); response.frame(it.streamFrame) }
            pipeline.interceptLLMStreamingFailed(this) { response.failure = it.error }
            pipeline.interceptLLMStreamingCompleted(this) {
                withContext(NonCancellable) {
                    config.saveResponse()
                    if (response.success) pipeline.onLLMCallCompleted(it.eventId, it.executionInfo, it.context, it.runId, it.prompt, it.model, it.tools,
                        Message.Assistant(response.text.toString(), response.end!!.metaInfo))
                    else pipeline.onLLMCallFailed(it.eventId, it.executionInfo, it.context, it.runId, it.prompt, it.model, it.tools,
                        response.failure ?: IllegalStateException("Stream interrupted"))
                }
            }
            return ToolLoopFeature()
        }
    }
}

private suspend fun AIAgentGraphContextBase.toolCall(call: SessionEntry.ToolCall, name: String, execute: suspend () -> SessionEntry.ToolResult): SessionEntry.ToolResult {
    val event = Uuid.random().toString()
    val args = JSONObject(emptyMap())
    pipeline.onToolCallStarting(event, executionInfo, this, runId, call.id, name, null, args)
    return try {
        execute().also {
            if (it.isError) pipeline.onToolCallFailed(event, executionInfo, this, runId, call.id, name, null, args, "Tool failed", IllegalStateException("Tool failed"))
            else pipeline.onToolCallCompleted(event, executionInfo, this, runId, call.id, name, null, args, null)
        }
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            pipeline.onToolCallFailed(event, executionInfo, this@toolCall, runId, call.id, name, null, args, "Tool interrupted", e)
        }
        throw e
    }
}

internal fun canonical(args: String) = runCatching { Json.parseToJsonElement(args).deterministic().toString() }.getOrDefault(args)
