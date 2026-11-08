@file:OptIn(ai.koog.agents.core.annotation.InternalAgentsApi::class)

package kinuvo.harness.core

import kinuvo.harness.core.tools.ToolNames
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.tools.ToolExecutor
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry

internal fun chatStrategy(session: RunSession, executor: ToolExecutor, stopped: (String) -> Unit) = strategy<Unit, Unit>("kinuvoChat") {
    edge(nodeStart forwardTo chatTurn(session, executor, stopped, nodeFinish))
}

/** The Session's history, compaction and the tool loop, ending at [finish]; returns the node to start from. */
internal fun AIAgentSubgraphBuilderBase<Unit, Unit>.chatTurn(session: RunSession, executor: ToolExecutor, stopped: (String) -> Unit, finish: AIAgentNodeBase<Unit, *>): AIAgentNodeBase<Unit, Unit> {
    val loop = ToolLoop(session.messages, response = session.response, executor = { executor }, toolName = session::toolName,
        callsPerTurn = session.profile.tools.callsPerTurn, answerNudge = session.profile.tools.callsPerTurn != null,
        refuse = { call -> if (session.profile.tools.callsPerTurn != null && call.name == ToolNames.MEMORY && !REMEMBER.containsMatchIn(session.latestUserText())) MEMORY_UNASKED else null },
        maxRequests = session.profile.maxSteps, parallelCalls = session.profile.tools.parallelCalls,
        repeated = { call ->
            session.toolStarted(call.id)
            session.emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
            SessionEntry.ToolResult(call.id, call.name, "This exact call already failed in this turn. Don't repeat it; try something else or answer the user.", isError = true, identity = call.identity)
        },
        repeatable = executor::repeatable,
        skipped = { call, text, error ->
            session.toolStarted(call.id)
            session.emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
            SessionEntry.ToolResult(call.id, call.name, text, isError = error, identity = call.identity)
        },
        completed = { call ->
            session.toolStarted(call.id)
            session.emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
            SessionEntry.ToolResult(call.id, call.name, "This exact action already succeeded. Use its result and answer the user; don't repeat it.", isError = true, identity = call.identity)
        },
        onResponse = {
            if (session.text().isNotEmpty()) session.emit(AgentEvent.TextMessageEnd(session.messageId, session.text()))
        },
        onResult = { result ->
            session.append(result)
            session.emit(AgentEvent.ToolCallResult(result.id, result.output, result.isError, result.identity))
        },
        onFrame = { frame -> when (frame) {
            is StreamFrame.TextDelta -> session.emit(AgentEvent.TextMessageContent(session.messageId, frame.text))
            is StreamFrame.ReasoningDelta -> (frame.summary ?: frame.text)?.let { session.emit(AgentEvent.ThinkingContent(session.messageId, it)) }
            else -> Unit
        } },
        stopped = stopped,
    )
    val checkHistory by node<Unit, Unit> {
        loop.checkRepeats()
        llm.writeSession { prompt = session.load() }
    }
    val compactHistory by nodeLLMCompressHistory<Unit>(name = COMPACT_HISTORY, strategy = session.compressionStrategy)
    edge(checkHistory forwardTo compactHistory onCondition { session.needsCompression(llm.prompt) })
    edge(checkHistory forwardTo loop.request onCondition { !session.needsCompression(llm.prompt) })
    edge(compactHistory forwardTo loop.request)
    loop.connect(this, checkHistory, finish)
    return checkHistory
}

/** Small models save facts they were asked about; they may write Memory only when the user asks them to remember something. */
internal val REMEMBER = Regex("""(?i)\b(remember|memori[sz]e|don'?t forget|keep in mind|note (that|down)|save (that|this)|recuerda|acu[eé]rdate|no olvides|apunta|guarda)""")
internal const val MEMORY_UNASKED = "Only save to Memory when the user asks you to remember something. Answer the user instead."
