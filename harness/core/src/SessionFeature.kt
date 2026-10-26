package kinuvo.harness.core

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.tools.Tool
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.RequestKind
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.NonCancellable
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
            pipeline.interceptNodeExecutionStarting(this) {
                if (it.node.name == COMPACT_HISTORY) it.context.llm.writeSession { prompt = session.beforeCompression(prompt) }
            }
            pipeline.interceptNodeExecutionCompleted(this) {
                if (it.node.name == COMPACT_HISTORY) it.context.llm.writeSession { prompt = session.afterCompression(prompt) }
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
) {
    val messages = SessionMessages(model)
    private val identities = callable.associate { it.descriptor.name to it.identity }
    fun toolName(name: String) = name.takeIf { it in identities } ?: "unknown"
    private var instructions = ""
    private var request = 0
    var messageId = ""
        private set
    val response = StreamResponse { identities[it] }
    val calls get() = response.calls
    val requests = ModelRequests(agent.config, { if (compressing) RequestKind.Compaction else kind }) {
        append(SessionEntry.Measurement(it))
    }
    private var responseSaved = true
    private val startedCalls = mutableSetOf<String>()
    private var compressing = false
    private var before: Prompt? = null
    private var retained: List<Message> = emptyList()
    private var systems: List<Message> = emptyList()
    private val budget = (contextTokens ?: model.model.contextLength?.toInt())?.let { it - minOf(4096, it / 4) }
    private val threshold = minOf(agent.config.compactAtTokens, budget ?: Int.MAX_VALUE)

    val compressionStrategy get() = HistoryCompression { (budget ?: 24_576) - instructions.length / 4 }

    suspend fun load(): Prompt {
        val entries = store.entries(agent, sessionId)
        instructions = entries.filterIsInstance<SessionEntry.Started>().firstOrNull()?.instructions.orEmpty()
        return messages.prompt(instructions, entries.activeHistory().recentScreens())
    }

    suspend fun startRequest() {
        if (!compressing) {
            messageId = "$runId-${request++}"
            response.messageId = messageId
            responseSaved = false
        }
    }

    suspend fun saveResponse() = withContext(NonCancellable) {
        if (responseSaved) return@withContext
        responseSaved = true
        response.reasoning.forEach { append(it) }
        if (response.text.isNotEmpty()) append(SessionEntry.AssistantMessage(
            if (response.success) text() else "${text()}\n\n[interrupted]", messageId))
        requests.finish(response.success)
        calls.forEach { append(it) }
    }

    fun text() = response.text.toString()

    suspend fun append(entry: SessionEntry) = withContext(NonCancellable) { store.append(agent, sessionId, entry) }

    fun toolStarted(id: String) { startedCalls += id }

    suspend fun closePending(reason: String, unstarted: String, notify: suspend (AgentEvent) -> Unit) = withContext(NonCancellable) {
        val all = store.entries(agent, sessionId)
        val answered = all.filterIsInstance<SessionEntry.ToolResult>().map { it.id }.toSet()
        val pending = all.filterIsInstance<SessionEntry.ToolCall>().filter { it.id !in answered }
        val results = pending.map {
            SessionEntry.ToolResult(it.id, it.name, if (it.id in startedCalls) reason else unstarted, isError = true, identity = it.identity)
                .also { append(it) }
        }
        pending.zip(results).forEach { (call, result) ->
            if (call.id !in startedCalls) notify(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
            notify(AgentEvent.ToolCallResult(result.id, result.output, result.isError, result.identity))
        }
    }

    fun needsCompression(prompt: Prompt): Boolean {
        if (estimate(prompt) <= threshold) return false
        if (olderMessages(prompt).isNotEmpty()) return true
        check(budget == null || estimate(prompt) <= budget) { "The current request and tools exceed this model's context window. Shorten the request or select a larger model." }
        return false
    }

    suspend fun beforeCompression(current: Prompt): Prompt {
        val active = store.entries(agent, sessionId).activeHistory().activations()
        val existing = messages.entries(current.messages).filterIsInstance<SessionEntry.Activation>().map { it.name }.toSet()
        val prompt = current.withMessages { it + messages.messages(active.filterNot { it.name in existing }) }
        before = current
        systems = prompt.messages.filterIsInstance<Message.System>()
        val older = olderMessages(prompt)
        retained = prompt.messages.filter { message -> message !is Message.System && older.none { it === message } }
        check(older.isNotEmpty()) { "The current request and tools exceed this model's context window. Shorten the request or select a larger model." }
        compressing = true
        return prompt.withMessages { older }
    }

    suspend fun afterCompression(prompt: Prompt): Prompt {
        val original = requireNotNull(before)
        val result = prompt.withMessages { systems + it + retained }
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

    private fun olderMessages(prompt: Prompt): List<Message> {
        val userAt = prompt.messages.indexOfLast(messages::user)
        var exchangeAt = prompt.messages.indexOfLast { message -> message.parts.any { it is ai.koog.prompt.message.MessagePart.Tool.Call } }
        if (exchangeAt <= userAt) exchangeAt = prompt.messages.size
        else while (exchangeAt > userAt + 1 && prompt.messages[exchangeAt - 1] is Message.Assistant) exchangeAt--
        return prompt.messages.filterIndexed { index, message ->
            message !is Message.System && index != userAt && index < exchangeAt
        }
    }

    private fun estimate(prompt: Prompt): Int = messages.entries(prompt.messages).estimatedTokens(instructions, tools)
}

internal const val COMPACT_HISTORY = "compactHistory"
