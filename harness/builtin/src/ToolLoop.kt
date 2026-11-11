@file:OptIn(ai.koog.agents.core.annotation.InternalAgentsApi::class)

package kinuvo.harness.builtin

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.serialization.JSONObject
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.plugin.ToolIdentity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid
import kinuvo.harness.queryWords

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
    private val calls: () -> ToolCalls,
    private val toolName: (String) -> String,
    private val repeated: suspend (SessionEntry.ToolCall) -> SessionEntry.ToolResult,
    private val repeatable: (String) -> Boolean = { true },
    private val completed: suspend (SessionEntry.ToolCall) -> SessionEntry.ToolResult = { call ->
        SessionEntry.ToolResult(call.id, call.name, "This exact action already succeeded. Use its result and answer the user; don't repeat it.", isError = true, identity = call.identity)
    },
    private val skipped: suspend (SessionEntry.ToolCall, String, Boolean) -> SessionEntry.ToolResult = { call, text, error ->
        SessionEntry.ToolResult(call.id, call.name, text, isError = error, identity = call.identity)
    },
    private val onResponse: suspend () -> Unit = {},
    private val onResult: suspend (SessionEntry.ToolResult) -> Unit = {},
    private val onFrame: suspend (StreamFrame) -> Unit = {},
    private val maxRequests: Int = Int.MAX_VALUE,
    private val parallelCalls: Boolean = true,
    private val callsPerTurn: Int? = null,
    private val answerNudge: Boolean = false,
    /** A reason not to run this call, e.g. a small model saving to Memory unasked; the turn then ends with an answer. */
    /** After a successful search, the next request answers: Micro models otherwise repeat the same search. */
    private val answerAfterSearch: Boolean = false,
    /** Tools left out of this request, e.g. Memory for a small model the user didn't ask to remember anything. */
    private val hidden: suspend () -> Set<String> = { emptySet() },
    private val refuse: suspend (SessionEntry.ToolCall) -> String? = { null },
) {
    private var requests = 0
    private val searches = mutableMapOf<String, MutableList<Set<String>>>()
    private var forceAnswer = false
    private val failed = mutableSetOf<Pair<String, String>>()
    private var repeats = 0
    private var repeatedSuccess = false
    private var lastSuccessful: Pair<String, String>? = null
    private var pending = emptyList<SessionEntry.ToolCall>()

    fun checkRepeats() = check(repeats < 2) { if (repeatedSuccess) "Stopped: the model kept repeating an action that already succeeded" else "Stopped: the model kept repeating a tool call that failed" }

    val request by node<Unit, Unit>("streamResponse") {
        checkRepeats()
        val finalAnswer = requests++ >= maxRequests || forceAnswer || answerNudge && repeats > 0 || callsPerTurn?.let { calls().executedCalls >= it } == true
        val hide = if (finalAnswer) emptySet() else hidden()
        llm.writeSession {
            var answering = finalAnswer
            fun answerNow() {
                tools = emptyList()
                prompt = prompt.withMessages { it + Message.System(ANSWER_NOW, RequestMetaInfo.Empty) }
            }
            if (finalAnswer) answerNow()
            else {
                if (hide.isNotEmpty()) tools = tools.filterNot { it.name in hide }
                if (answerNudge && calls().executedCalls > 0) prompt = prompt.withMessages { it + Message.System(ANSWER_NUDGE, RequestMetaInfo.Empty) }
            }
            try { requestLLMStreaming().requireEndFrame().collect { onFrame(it) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (finalAnswer) throw RequestLimitReached()
                if (!answerNudge) throw e
                // Small models can fail mid-turn (Apple's guardrails refuse some requests): one try at answering without tools.
                answerNow()
                answering = true
                try { requestLLMStreaming().requireEndFrame().collect { onFrame(it) } }
                catch (c: CancellationException) { throw c }
                catch (_: Exception) { throw e }
            }
            if (answering && response.calls.isNotEmpty()) throw RequestLimitReached()
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
            if (key == lastSuccessful && !repeatable(call.name)) { repeats++; repeatedSuccess = true; completed(call) }
            else if (key in failed && !calls().search(call.name, call.args)) { repeats++; repeated(call) }
            else {
                val query = if (calls().search(call.name, call.args)) searchWords(call.args) else emptySet()
                val previous = searches.getOrPut(call.name) { mutableListOf() }
                val refusal = refuse(call)
                if (refusal != null) skipped(call, refusal, true)
                else if (query.isNotEmpty() && previous.any { similarSearch(it, query) })
                    skipped(call, SEARCHED, false).also { forceAnswer = true }
                else if (callsPerTurn?.let { calls().executedCalls >= it } == true && !calls().internal(call.name)) skipped(call, SKIPPED_CALL, true)
                else {
                    if (query.isNotEmpty()) previous += query
                    calls().call(call).also { if (answerAfterSearch && query.isNotEmpty() && !it.isError) forceAnswer = true }
                }
            }
        }
        if (result.isError) failed += key else lastSuccessful = key
        onResult(result)
        llm.writeSession { prompt = prompt.withMessages { it + messages.messages(listOf(result)) } }
        pending = pending.drop(1)
        if (!parallelCalls) {
            pending.forEach { call ->
                val result = skipped(call, SKIPPED_CALL, true)
                onResult(result)
                llm.writeSession { prompt = prompt.withMessages { it + messages.messages(listOf(result)) } }
            }
            pending = emptyList()
        }
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

internal const val SKIPPED_CALL = "Skipped: only one tool call is allowed per turn. Call this tool in the next response if still needed."

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

internal class RequestLimitReached : IllegalStateException()

const val SEARCHED = "You already searched for this; use the results above and answer."
const val ANSWER_NOW = "Answer the user's question now in a few sentences, using the tool results above. Don't ask the user to clarify, and don't say you'll remember or save anything; if the results don't answer it, say what you found and what's missing."
const val ANSWER_NUDGE = "Answer the user from these results unless something essential is missing. Don't ask the user to clarify."

internal fun searchWords(args: String): Set<String> = runCatching {
    val query = Json.parseToJsonElement(args).jsonObject["query"]?.jsonPrimitive?.content.orEmpty()
    queryWords(query)
}.getOrDefault(emptySet())

internal fun similarSearch(a: Set<String>, b: Set<String>) = a.isNotEmpty() && b.isNotEmpty() &&
    a.intersect(b).size.toDouble() / a.union(b).size >= 0.6
