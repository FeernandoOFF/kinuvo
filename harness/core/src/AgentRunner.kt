package kinuvo.harness.core

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.RoutineRef

import ai.koog.prompt.streaming.StreamFrame
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.protocol.RequestKind
import kinuvo.protocol.RequestMetrics
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.tools.AskUserTool
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.string
import kinuvo.harness.core.tools.ToolKind
import kinuvo.harness.core.tools.ToolRun
import kinuvo.harness.core.tools.indexedTools
import kinuvo.harness.core.tools.policyKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/** Runs one user turn: streams model output, executes tool calls, loops until the model stops calling tools. */
class AgentRunner(
    private val agent: Agent,
    private val store: Store,
    private val model: Model,
    private val tools: List<Tool>,
    private val warnings: List<String> = emptyList(),
    private val context: String = "",
    private val approvals: Approvals = Approvals.none(),
    /** Where `ask_user` forms wait for answers; without it the tool fails. */
    private val questions: Broker<Map<String, List<String>>?>? = null,
    private val maxSteps: Int = 30,
    advertisedTools: List<ToolDescriptor> = tools.map { it.descriptor },
    private val contextTokens: Int? = null,
    private val requestKind: RequestKind = RequestKind.Chat,
    private val onRequest: (String, List<SessionEntry>, List<ToolDescriptor>) -> Unit = { _, _, _ -> },
    /** Stores a file a tool shows the model; without it tools can't attach files. */
    private val attach: ((name: String, bytes: ByteArray) -> kinuvo.protocol.Attachment)? = null,
) {
    private val advertisedTools = advertisedTools.sortedBy { it.name }
    /** Every tool a Script or catalog call may reach, including catalogued ones the model isn't sent. */
    private val callable = (advertisedTools + tools.map { it.descriptor }).distinctBy { it.name }
    private val toolsByName = tools.indexedTools()

    fun run(
        sessionId: String,
        userText: String,
        activation: SessionEntry.Activation? = null,
        runId: String = Uuid.random().toString(),
        routine: RoutineRef? = null,
        attachments: List<kinuvo.protocol.Attachment> = emptyList(),
        voice: Boolean = false,
    ): Flow<AgentEvent> = flow {
        emit(AgentEvent.RunStarted(runId))
        warnings.forEach { emit(AgentEvent.Warning(it)) }
        try {
            if (context.isNotBlank() && store.entries(agent, sessionId).activeHistory().filterIsInstance<SessionEntry.Context>().lastOrNull()?.content != context) {
                store.append(agent, sessionId, SessionEntry.Context(context))
            }
            activation?.let { store.append(agent, sessionId, it) }
            store.append(agent, sessionId, SessionEntry.UserMessage(userText, routine, attachments, voice))
            // Calls that failed this run, by name and arguments; a model repeating one is told so instead of running it again.
            val failed = mutableSetOf<Pair<String, String>>()
            var repeats = 0
            for (step in 0 until maxSteps) {
                val instructions = store.entries(agent, sessionId)
                    .filterIsInstance<SessionEntry.Started>().firstOrNull()?.instructions.orEmpty()
                if (requestKind != RequestKind.Reflection) compactIfNeeded(sessionId, instructions)?.let { emit(it) }
                val entries = store.entries(agent, sessionId).activeHistory().recentScreens()
                val messageId = "$runId-$step"
                val text = StringBuilder()
                val reasoning = mutableListOf<SessionEntry.Reasoning>()
                val calls = mutableListOf<SessionEntry.ToolCall>()

                var metrics: RequestMetrics? = null
                try {
                    onRequest(instructions, entries, advertisedTools)
                    model.measured(agent.config, requestKind, instructions, entries, advertisedTools) { metrics = it }.collect { frame ->
                        when (frame) {
                            is StreamFrame.TextDelta -> {
                                text.append(frame.text)
                                emit(AgentEvent.TextMessageContent(messageId, frame.text))
                            }
                            is StreamFrame.ReasoningDelta -> (frame.summary ?: frame.text)?.let {
                                emit(AgentEvent.ThinkingContent(messageId, it))
                            }
                            // Koog 1.3 drops summary-less reasoning items along with their encrypted content,
                            // so those aren't carried: https://github.com/JetBrains/koog/issues/2287
                            is StreamFrame.ReasoningComplete -> reasoning += SessionEntry.Reasoning(
                                text = (frame.summary?.takeIf { it.isNotEmpty() } ?: frame.content).joinToString("\n\n"),
                                id = frame.id,
                                encrypted = frame.encrypted,
                                messageId = messageId,
                            )
                            is StreamFrame.ToolCallComplete ->
                                calls += SessionEntry.ToolCall(frame.id ?: Uuid.random().toString(), frame.name, frame.content, toolsByName[frame.name]?.identity)
                            else -> Unit
                        }
                    }
                } catch (e: Throwable) {
                    // Keep what was streamed so the transcript has no unanswered turn, even when cancelled.
                    if (text.isNotEmpty()) saving { store.append(agent, sessionId, SessionEntry.AssistantMessage("$text\n\n[interrupted]", messageId)) }
                    metrics?.let { saving { store.append(agent, sessionId, SessionEntry.Measurement(it)) } }
                    throw e
                }

                saving {
                    reasoning.forEach { store.append(agent, sessionId, it) }
                    if (text.isNotEmpty()) store.append(agent, sessionId, SessionEntry.AssistantMessage(text.toString(), messageId))
                    metrics?.let { store.append(agent, sessionId, SessionEntry.Measurement(it)) }
                }
                if (text.isNotEmpty()) emit(AgentEvent.TextMessageEnd(messageId, text.toString()))
                if (calls.isEmpty()) {
                    emit(AgentEvent.RunFinished(runId))
                    return@flow
                }

                for (call in calls) {
                    store.append(agent, sessionId, call)
                    var stopped = "Cancelled by user"
                    val key = call.name to canonical(call.args)
                    val result = if (key in failed) {
                        repeats++
                        emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
                        SessionEntry.ToolResult(call.id, call.name, "This exact call already failed in this turn. Don't repeat it; try something else or answer the user.", isError = true, identity = call.identity)
                    } else try {
                        emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
                        perform(call, call.id) { stopped = it }
                    } catch (e: CancellationException) {
                        saving { store.append(agent, sessionId, SessionEntry.ToolResult(call.id, call.name, stopped, isError = true, identity = call.identity)) }
                        throw e
                    }
                    saving { store.append(agent, sessionId, result) }
                    emit(AgentEvent.ToolCallResult(call.id, result.output, result.isError, result.identity))
                    if (result.isError) failed += key
                }
                check(repeats < MAX_REPEATS) { "Stopped: the model kept repeating a tool call that failed" }
            }
            error("Stopped after $maxSteps model steps. The work is unfinished; send another message to continue.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.RunError(runId, e.message ?: e.toString()))
        }
    }

    /**
     * Runs [call] once its Approval allows it. [approvalId] is the stored call the Approval shows on: [call]'s own, or
     * the Script's for a call made from one. [stopped] is told what to record if the run is cancelled meanwhile.
     */
    private suspend fun FlowCollector<AgentEvent>.perform(call: SessionEntry.ToolCall, approvalId: String, stopped: (String) -> Unit = {}): SessionEntry.ToolResult {
        val tool = toolsByName[call.name]
        val verdict = tool?.let { approvals.policy?.decide(it, call.args) } ?: Verdict.Run
        val decision = when {
            verdict is Verdict.Block -> null
            verdict !is Verdict.Ask -> ApprovalDecision.AllowOnce
            approvals.unattended != null -> approvals.unattended
            else -> {
                stopped("Denied (stopped)")
                val pending = approvals.broker.register()
                try {
                    val a = verdict.assessment
                    val hint = when (tool?.kind) {
                        ToolKind.Shell -> verdict.trustKeys.joinToString(", ")
                        ToolKind.ComputerAction -> verdict.trustKeys.joinToString(", ") { it.removePrefix("${tool.policyKey} ") }
                        else -> ""
                    }.ifEmpty { null }
                    emit(AgentEvent.ApprovalRequested(pending.id, approvalId, call.name, call.args, hint, a.risk, a.reason, verdict.trustKeys.isNotEmpty(), tool?.identity))
                    pending.await().also {
                        stopped("Cancelled by user")
                        emit(AgentEvent.ApprovalResolved(approvalId, it !is ApprovalDecision.Deny))
                    }
                } finally {
                    pending.close()
                }
            }
        }
        return when (decision) {
            null -> SessionEntry.ToolResult(call.id, call.name, (verdict as Verdict.Block).reason + " Don't retry it; find another way or ask the user.", isError = true)
            is ApprovalDecision.Deny -> SessionEntry.ToolResult(
                id = call.id, name = call.name,
                output = "The user denied this call." + (decision.note?.takeIf { it.isNotBlank() }?.let { " Note: $it" } ?: ""),
                isError = true,
            )
            else -> {
                if (decision == ApprovalDecision.AlwaysAllow && verdict is Verdict.Ask && tool != null) approvals.policy?.trust(tool, verdict.trustKeys)
                if (tool?.kind == ToolKind.AskUser) ask(call, approvalId) else execute(call, approvalId, stopped)
            }
        }.copy(identity = tool?.identity)
    }

    /** What [call]'s tool may call: the run's other tools, each shown as its own call and answered like one. */
    private fun FlowCollector<AgentEvent>.toolRun(call: SessionEntry.ToolCall, approvalId: String, stopped: (String) -> Unit,
                                                  attached: MutableList<kinuvo.protocol.Attachment>) = object : ToolRun {
        private var count = 0
        override val tools = callable.filter { it.name != call.name }
        override suspend fun call(name: String, args: String): SessionEntry.ToolResult {
            val inner = SessionEntry.ToolCall("${call.id}-${++count}", name, args, toolsByName[name]?.identity)
            if (tools.none { it.name == name }) return SessionEntry.ToolResult(inner.id, name, "Unknown tool '$name'", isError = true)
            emit(AgentEvent.ToolCallStart(inner.id, name, args, inner.identity))
            return perform(inner, approvalId, stopped).also {
                attached += it.attachments
                emit(AgentEvent.ToolCallResult(inner.id, it.output, it.isError, it.identity))
            }
        }
        override suspend fun attach(name: String, bytes: ByteArray) =
            requireNotNull(attach) { "Files can't be shown to the model in this run" }(name, bytes).also { attached += it }
    }

    /** Compacts older turns or completed tool exchanges, preserving the current request and latest context. */
    private suspend fun compactIfNeeded(sessionId: String, instructions: String): AgentEvent.Compacted? {
        val all = store.entries(agent, sessionId)
        val history = all.activeHistory().recentScreens()
        val budget = contextTokens?.let { it - minOf(4096, it / 4) }
        val threshold = minOf(agent.config.compactAtTokens, budget ?: Int.MAX_VALUE)
        if (history.estimatedTokens(instructions, advertisedTools) <= threshold) return null
        var currentTurn = all.indexOfLast { it is SessionEntry.UserMessage }
            .let { if (it > 0 && all[it - 1] is SessionEntry.Activation) it - 1 else it }
        while (currentTurn > 0 && (all[currentTurn - 1] is SessionEntry.Context || all[currentTurn - 1] is SessionEntry.Activation)) currentTurn--
        val userAt = all.indexOfLast { it is SessionEntry.UserMessage }
        val lastResult = all.indexOfLast { it is SessionEntry.ToolResult }
        var keepFrom = currentTurn
        var user: SessionEntry.UserMessage? = null
        if (lastResult > userAt) {
            val lastCall = all.indexOfLast { it is SessionEntry.ToolCall }
            // Files from this turn's tools stay in full; a summary would only keep their names.
            val attached = all.drop(userAt).firstOrNull { it is SessionEntry.ToolResult && it.attachments.any { a -> !a.name.startsWith(SCREENSHOT_PREFIX) } }
                ?.let { r -> all.indexOfFirst { it is SessionEntry.ToolCall && it.id == (r as SessionEntry.ToolResult).id } }
            keepFrom = when {
                attached != null -> attached
                all.drop(lastCall).estimatedTokens("") < threshold / 2 && all.take(lastCall).count { it is SessionEntry.ToolResult } > 0 -> lastCall
                else -> lastResult + 1
            }
            user = all[userAt] as SessionEntry.UserMessage
        }
        val toSummarize = all.take(keepFrom).activeHistory()
        if (toSummarize.isEmpty() || toSummarize.all { it is SessionEntry.Context || it is SessionEntry.Activation || it is SessionEntry.Compaction }) {
            check(budget == null || history.estimatedTokens(instructions, advertisedTools) <= budget) { "The current request and tools exceed this model's context window. Shorten the request or select a larger model." }
            return null
        }
        val summary = summarize(sessionId, toSummarize, budget)
        val activations = toSummarize.activations()
        val compaction = SessionEntry.Compaction(summary, keepFrom, activations, user)
        val prospective = (all + compaction).activeHistory().recentScreens()
        check(budget == null || prospective.estimatedTokens(instructions, advertisedTools) <= budget) { "Compaction could not fit this request and tools in the model's context window. Shorten the request or select a larger model." }
        check(prospective.estimatedTokens(instructions, advertisedTools) < history.estimatedTokens(instructions, advertisedTools)) { "Compaction did not reduce the context. Shorten the request or select a larger model." }
        store.append(agent, sessionId, compaction)
        return AgentEvent.Compacted(summary)
    }

    private suspend fun summarize(sessionId: String, history: List<SessionEntry>, budget: Int?): String {
        suspend fun request(entries: List<SessionEntry>): String {
            val text = StringBuilder()
            var metrics: RequestMetrics? = null
            try {
                model.measured(agent.config, RequestKind.Compaction, COMPACTION_INSTRUCTIONS, entries, emptyList()) { metrics = it }.collect {
                    if (it is StreamFrame.TextDelta) text.append(it.text)
                }
            } finally { metrics?.let { saving { store.append(agent, sessionId, SessionEntry.Measurement(it)) } } }
            check(text.isNotBlank()) { "Compaction produced an empty summary" }
            return text.toString()
        }
        if (budget == null || history.estimatedTokens(COMPACTION_INSTRUCTIONS) <= budget) return request(history)
        val chunkChars = (budget - COMPACTION_INSTRUCTIONS.length / 4 - 64) * 4
        check(chunkChars >= 256) { "This model's context window is too small for Compaction" }
        var text = history.joinToString("\n\n") { entry ->
            when (entry) {
                is SessionEntry.Reasoning -> entry.text
                else -> entry.toString()
            }
        }
        repeat(4) {
            val chunks = text.chunked(chunkChars)
            val summaries = chunks.map { request(listOf(SessionEntry.UserMessage("Conversation excerpt:\n$it"))) }
            val reduced = summaries.joinToString("\n\n")
            if (reduced.length <= chunkChars) return if (summaries.size == 1) reduced else request(listOf(SessionEntry.UserMessage(reduced)))
            check(reduced.length < text.length) { "Compaction did not reduce the context" }
            text = reduced
        }
        error("The conversation is too large to compact for this model")
    }

    /** Writes that must land even when the run is being cancelled. */
    private suspend fun saving(block: suspend () -> Unit) = withContext(NonCancellable) { block() }

    private suspend fun FlowCollector<AgentEvent>.ask(call: SessionEntry.ToolCall, formId: String = call.id): SessionEntry.ToolResult {
        val broker = questions ?: return SessionEntry.ToolResult(call.id, call.name, "There's no user to ask in this run.", isError = true)
        val (title, list) = try {
            val args = Json.parseToJsonElement(call.args.ifBlank { "{}" }).jsonObject
            args.string("title")?.ifBlank { null } to AskUserTool.questions(args)
        } catch (e: Exception) {
            return SessionEntry.ToolResult(call.id, call.name, "Error: ${e.message}", isError = true)
        }
        val pending = broker.register()
        try {
            emit(AgentEvent.QuestionsAsked(pending.id, formId, title, list))
            val answers = pending.await()
            emit(AgentEvent.QuestionsAnswered(formId))
            return SessionEntry.ToolResult(call.id, call.name, AskUserTool.result(list, answers))
        } finally {
            pending.close()
        }
    }

    private suspend fun FlowCollector<AgentEvent>.execute(call: SessionEntry.ToolCall, approvalId: String, stopped: (String) -> Unit): SessionEntry.ToolResult {
        val tool = toolsByName[call.name]
            ?: return SessionEntry.ToolResult(call.id, call.name, "Unknown tool '${call.name}'", isError = true)
        val attached = mutableListOf<kinuvo.protocol.Attachment>()
        return try {
            val args = Json.parseToJsonElement(call.args.ifBlank { "{}" }).jsonObject
            val output = tool.execute(args, call.id, toolRun(call, approvalId, stopped, attached))
            SessionEntry.ToolResult(call.id, call.name, output, attachments = attached)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionEntry.ToolResult(call.id, call.name, "Error: ${e.message}", isError = true, attachments = attached)
        }
    }
}

private const val MAX_REPEATS = 2

/** [args] with its keys sorted, so the same call matches however the model orders them. */
private fun canonical(args: String) = runCatching { Json.parseToJsonElement(args).deterministic().toString() }.getOrDefault(args)
