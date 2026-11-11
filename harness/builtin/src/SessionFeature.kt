package kinuvo.harness.builtin

import kinuvo.harness.modelText
import kinuvo.harness.Agent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import kinuvo.harness.SessionStorage
import kinuvo.harness.builtin.tools.toDescriptor
import kinuvo.harness.ToolProvider
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.usage.RequestKind
import kinuvo.protocol.session.SessionEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kinuvo.harness.ModelProfile
import kinuvo.harness.PromptStyle
import kinuvo.harness.liveVoiceInstructions

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
    private val store: SessionStorage,
    private val sessionId: String,
    private val runId: String,
    private val model: AgentModel,
    provider: ToolProvider,
    private val kind: RequestKind,
    private val contextTokens: Int?,
    private val latestTurnOnly: Boolean,
    val profile: ModelProfile,
    val emit: suspend (AgentEvent) -> Unit,
    private val voice: Boolean = false,
) {
    val tools = provider.advertised.map { it.toDescriptor() }
    val messages = SessionMessages(model, tools.any { it.name == ToolNames.GENERATE_IMAGE })
    private val callablePaging = provider.tools.filter { it.paging }.map { it.name }.toSet()
    private val identities = provider.tools.associate { it.name to it.identity }
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
    private var compressionSkipped = false
    private var counted: Pair<Prompt, Int>? = null
    private val logger = KotlinLogging.logger("kinuvo.context")
    private val threshold = minOf(agent.config.compactAtTokens, budget ?: Int.MAX_VALUE)

    val compressionStrategy get() = HistoryCompression { (budget ?: 24_576) - instructions.length / 4 }

    suspend fun load(): Prompt {
        val entries = store.entries(agent.id, sessionId)
        instructions = if (profile.prompt == PromptStyle.Full) entries.filterIsInstance<SessionEntry.Started>().firstOrNull()?.instructions.orEmpty() else ""
        if (voice) instructions += "\n\n$liveVoiceInstructions"
        val history = entries.activeHistory().withoutBlockedRepeats().let { if (latestTurnOnly) it.latestTurn() else it }
        val prompt = messages.limitedPrompt(instructions, history.recentScreens(), profile.resultLimits, callablePaging)
        return earlierQuestions(entries.activeHistory())?.let { note ->
            prompt.withMessages { it.takeWhile { m -> m is Message.System } + Message.System(note, RequestMetaInfo.Empty) + it.dropWhile { m -> m is Message.System } }
        } ?: prompt
    }

    private fun earlierQuestions(history: List<SessionEntry>): String? {
        val count = profile.history.earlierQuestions
        if (!latestTurnOnly || count <= 0) return null
        val asked = history.filterIsInstance<SessionEntry.UserMessage>().dropLast(1).takeLast(count)
            .map { it.modelText.trim().takeWhole(120) }.filter { it.isNotEmpty() }
        return asked.takeIf { it.isNotEmpty() }?.joinToString("\n", "Earlier in this conversation the user asked (for context only; answer the latest message):\n") { "- $it" }
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

    suspend fun latestUserText(): String = store.entries(agent.id, sessionId).lastOrNull { it is SessionEntry.UserMessage }?.let { (it as SessionEntry.UserMessage).text }.orEmpty()

    suspend fun append(entry: SessionEntry) = withContext(NonCancellable) { store.append(agent.id, sessionId, entry) }

    fun toolStarted(id: String) { startedCalls += id }

    suspend fun closePending(reason: String, unstarted: String, notify: suspend (AgentEvent) -> Unit) = withContext(NonCancellable) {
        val all = store.entries(agent.id, sessionId)
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

    suspend fun needsCompression(prompt: Prompt): Boolean {
        val size = estimate(prompt)
        if (size <= threshold || compressionSkipped && (budget == null || size <= budget)) return false
        val older = olderMessages(prompt)
        if (budget != null && size > budget) {
            val fixed = prompt.withMessages { list -> list.filter { message -> older.none { it === message } } }
            check(estimate(fixed) <= budget) { "Instructions, Memory, tools and the current request exceed this model's context window. ${diagnostics(fixed, estimate(fixed))}" }
        }
        // Summarizing a short exchange usually grows it. The hard input budget still applies.
        if (older.isNotEmpty() && (budget != null && size > budget || messages.entries(older).estimatedTokens("") >= 512)) return true
        check(budget == null || size <= budget) { "The current request and tools exceed this model's context window. ${diagnostics(prompt, size)} Shorten instructions, Memory or tool context, or select a larger model." }
        return false
    }

    suspend fun beforeCompression(current: Prompt): Prompt {
        val active = store.entries(agent.id, sessionId).activeHistory().activations()
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
        if (estimate(result) >= estimate(original) && (budget == null || estimate(original) <= budget)) {
            compressing = false
            before = null
            compressionSkipped = true
            return original
        }
        check(estimate(result) < estimate(original)) { "Compaction did not reduce the context. ${diagnostics(original, estimate(original))}" }
        check(budget == null || estimate(result) <= budget) { "Compaction could not fit this request and tools in the model's context window. ${diagnostics(result, estimate(result))}" }
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

    private suspend fun estimate(prompt: Prompt): Int {
        counted?.takeIf { it.first === prompt }?.let { return it.second }
        val size = model.estimateTokens?.invoke(prompt, tools) ?: messages.estimatedEntries(prompt.messages).estimatedTokens(instructions, tools)
        logger.debug { diagnostics(prompt, size) }
        counted = prompt to size
        return size
    }

    private fun diagnostics(prompt: Prompt, total: Int): String {
        val history = messages.entries(prompt.messages)
        val context = history.filterIsInstance<SessionEntry.Context>().sumOf { it.content.length } / 4
        val toolTokens = emptyList<SessionEntry>().estimatedTokens("", tools)
        val conversation = history.filterNot { it is SessionEntry.Context }.estimatedTokens("")
        return "Input $total tokens; budget $budget. Estimated components: instructions ${instructions.length / 4}, Memory/capabilities $context, tools $toolTokens, history $conversation."
    }
}

internal const val COMPACT_HISTORY = "compactHistory"
