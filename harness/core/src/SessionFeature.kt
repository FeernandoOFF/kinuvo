package kinuvo.harness.core

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.tools.Tool
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.RequestKind
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class SessionFeature private constructor() {
    class Config : FeatureConfig() {
        internal lateinit var session: RunSession
    }

    companion object : AIAgentGraphFeature<Config, SessionFeature> {
        override val key = createStorageKey<SessionFeature>("kinuvo-session")
        override fun createInitialConfig(agentConfig: AIAgentConfig) = Config()
        override fun install(config: Config, pipeline: AIAgentGraphPipeline): SessionFeature {
            val session = config.session
            pipeline.interceptStrategyStarting(this) { event ->
                event.context.llm.writeSession {
                    prompt = session.load()
                    tools = session.tools
                }
            }
            pipeline.interceptLLMStreamingStarting(this) { session.startRequest() }
            pipeline.interceptLLMStreamingFrameReceived(this) { session.frame(it.streamFrame) }
            pipeline.interceptLLMStreamingFailed(this) { session.streamFailed = true }
            pipeline.interceptLLMStreamingCompleted(this) { session.saveResponse() }
            pipeline.interceptLLMCallStarting(this) { session.startRequest() }
            pipeline.interceptLLMCallCompleted(this) {
                it.response?.let { response ->
                    session.measurement?.capture?.let { capture ->
                        capture.input = capture.input ?: response.metaInfo.inputTokensCount
                        capture.output = capture.output ?: response.metaInfo.outputTokensCount
                    }
                }
                session.saveMeasurement(true)
            }
            pipeline.interceptLLMCallFailed(this) { session.saveMeasurement(false) }
            pipeline.interceptNodeExecutionStarting(this) {
                if (it.node.name == COMPACT_HISTORY) it.context.llm.writeSession { prompt = session.beforeCompression(prompt) }
            }
            pipeline.interceptNodeExecutionCompleted(this) {
                if (it.node.name == COMPACT_HISTORY) it.context.llm.writeSession { prompt = session.afterCompression(prompt) }
                (it.output as? SessionEntry.ToolResult)?.let { result ->
                    session.append(result)
                    it.context.llm.writeSession { prompt = prompt.withMessages { it + session.messages.messages(listOf(result)) } }
                    session.emit(AgentEvent.ToolCallResult(result.id, result.output, result.isError, result.identity))
                }
            }
            return SessionFeature()
        }
    }
}

internal class RunSession(
    private val agent: Agent,
    private val store: Store,
    private val sessionId: String,
    private val runId: String,
    model: AgentModel,
    val tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    callable: List<Tool>,
    private val kind: RequestKind,
    private val contextTokens: Int?,
    val emit: suspend (AgentEvent) -> Unit,
    private val onRequest: (String, List<SessionEntry>, List<ai.koog.agents.core.tools.ToolDescriptor>) -> Unit,
) {
    val messages = SessionMessages(model)
    private val identities = callable.associate { it.descriptor.name to it.identity }
    private var instructions = ""
    private var request = 0
    var messageId = ""
        private set
    private var text = StringBuilder()
    private var response = mutableListOf<SessionEntry>()
    var calls: List<SessionEntry.ToolCall> = emptyList()
        private set
    var measurement: ModelMeasurement? = null
        private set
    private var ended = false
    var streamFailed = false
    private var compressing = false
    private var before: Prompt? = null
    private var retained: List<Message> = emptyList()
    private val budget = (contextTokens ?: model.model.contextLength?.toInt())?.let { it - minOf(4096, it / 4) }
    private val threshold = minOf(agent.config.compactAtTokens, budget ?: Int.MAX_VALUE)

    suspend fun load(): Prompt {
        val entries = store.entries(agent, sessionId)
        instructions = entries.filterIsInstance<SessionEntry.Started>().firstOrNull()?.instructions.orEmpty()
        return messages.prompt(instructions, entries.activeHistory().recentScreens())
    }

    suspend fun startRequest() {
        val capture = currentCoroutineContext()[TokenUsageCapture] ?: TokenUsageCapture()
        capture.input = null; capture.output = null; capture.cached = null; capture.cacheWrite = null; capture.completed = null
        measurement = ModelMeasurement(agent.config, if (compressing) RequestKind.Compaction else kind, capture)
        if (!compressing) {
            messageId = "$runId-${request++}"
            text = StringBuilder()
            response = mutableListOf()
            calls = emptyList()
            ended = false
            streamFailed = false
            onRequest(instructions, store.entries(agent, sessionId).activeHistory().recentScreens(), tools)
        }
    }

    fun frame(frame: StreamFrame) {
        measurement?.frame(frame)
        when (frame) {
            is StreamFrame.TextDelta -> text.append(frame.text)
            is StreamFrame.ReasoningComplete -> response += SessionEntry.Reasoning(
                (frame.summary?.takeIf { it.isNotEmpty() } ?: frame.content).joinToString("\n\n"), frame.id, frame.encrypted, messageId)
            is StreamFrame.ToolCallComplete -> response += SessionEntry.ToolCall(
                requireNotNull(frame.id) { "The model returned a tool call without an ID" }, frame.name, frame.content, identities[frame.name])
            is StreamFrame.End -> ended = true
            else -> Unit
        }
    }

    suspend fun saveResponse() = withContext(NonCancellable) {
        if (measurement == null) return@withContext
        val success = ended && !streamFailed
        response.filterIsInstance<SessionEntry.Reasoning>().forEach { append(it) }
        if (text.isNotEmpty()) append(SessionEntry.AssistantMessage(if (success) text.toString() else "$text\n\n[interrupted]", messageId))
        calls = response.filterIsInstance<SessionEntry.ToolCall>()
        saveMeasurement(success)
        calls.forEach { append(it) }
    }

    suspend fun saveMeasurement(success: Boolean) {
        val current = measurement ?: return
        measurement = null
        append(SessionEntry.Measurement(current.finish(success)))
    }

    fun responseMessages(): List<Message> = messages.messages(response.filterIsInstance<SessionEntry.Reasoning>() +
        listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { SessionEntry.AssistantMessage(it.toString(), messageId) }) + calls)

    fun text() = text.toString()

    suspend fun append(entry: SessionEntry) = withContext(NonCancellable) { store.append(agent, sessionId, entry) }

    suspend fun closePending(reason: String) = withContext(NonCancellable) {
        val all = store.entries(agent, sessionId)
        val answered = all.filterIsInstance<SessionEntry.ToolResult>().map { it.id }.toSet()
        all.filterIsInstance<SessionEntry.ToolCall>().filter { it.id !in answered }.forEach {
            append(SessionEntry.ToolResult(it.id, it.name, reason, isError = true, identity = it.identity))
        }
    }

    fun needsCompression(prompt: Prompt): Boolean {
        if (kind == RequestKind.Reflection || estimate(prompt) <= threshold) return false
        val userAt = prompt.messages.indexOfLast(messages::user)
        val compressible = prompt.messages.withIndex().any { (index, message) ->
            index != userAt && message !is Message.System && !(index > userAt && messages.files(message))
        }
        if (compressible) return true
        check(budget == null || estimate(prompt) <= budget) { "The current request and tools exceed this model's context window. Shorten the request or select a larger model." }
        return false
    }

    suspend fun beforeCompression(current: Prompt): Prompt {
        val active = store.entries(agent, sessionId).activeHistory().activations()
        val existing = messages.entries(current.messages).filterIsInstance<SessionEntry.Activation>().map { it.name }.toSet()
        val prompt = current.withMessages { it + messages.messages(active.filterNot { it.name in existing }) }
        before = current
        val userAt = prompt.messages.indexOfLast(messages::user)
        retained = prompt.messages.filterIndexed { index, message -> index == userAt || index > userAt && messages.files(message) }
        val older = prompt.messages.filterNot { it in retained }
        check(older.any { it !is Message.System }) { "The current request and tools exceed this model's context window. Shorten the request or select a larger model." }
        compressing = true
        return prompt.withMessages { older }
    }

    suspend fun afterCompression(prompt: Prompt): Prompt {
        val original = requireNotNull(before)
        val result = prompt.withMessages { it + retained }
        check(result.messages.any { it is Message.Assistant && it.textContent().isNotBlank() }) { "Compaction produced an empty summary" }
        check(estimate(result) < estimate(original)) { "Compaction did not reduce the context. Shorten the request or select a larger model." }
        check(budget == null || estimate(result) <= budget) { "Compaction could not fit this request and tools in the model's context window. Shorten the request or select a larger model." }
        val summary = prompt.messages.filterIsInstance<Message.Assistant>().last().textContent()
        append(SessionEntry.Compaction(summary, messages.entries(result.messages)))
        compressing = false
        before = null
        emit(AgentEvent.Compacted(summary))
        return result
    }

    private fun estimate(prompt: Prompt): Int = messages.entries(prompt.messages).estimatedTokens(instructions, tools)
}

internal const val COMPACT_HISTORY = "compactHistory"
