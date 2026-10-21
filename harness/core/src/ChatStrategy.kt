package kinuvo.harness.core

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.tools.ToolExecutor
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry

internal fun chatStrategy(session: RunSession, executor: ToolExecutor, stopped: (String) -> Unit) = strategy<Unit, Unit>("kinuvoChat") {
    val loop = ToolLoop(session.messages, session.response, { executor }, session::toolName,
        repeated = { call ->
            session.toolStarted(call.id)
            session.emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
            SessionEntry.ToolResult(call.id, call.name, "This exact call already failed in this turn. Don't repeat it; try something else or answer the user.", isError = true, identity = call.identity)
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
    edge(nodeStart forwardTo checkHistory)
    edge(checkHistory forwardTo compactHistory onCondition { session.needsCompression(llm.prompt) })
    edge(checkHistory forwardTo loop.request onCondition { !session.needsCompression(llm.prompt) })
    edge(compactHistory forwardTo loop.request)
    loop.connect(this, checkHistory)
}
