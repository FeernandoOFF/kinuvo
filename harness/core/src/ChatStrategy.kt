@file:OptIn(ai.koog.agents.core.annotation.InternalAgentsApi::class)

package kinuvo.harness.core

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
internal fun AIAgentSubgraphBuilderBase<Unit, Unit>.chatTurn(session: RunSession, executor: ToolExecutor, stopped: (String) -> Unit, finish: AIAgentNodeBase<Unit, *>, room: RoomRun? = null): AIAgentNodeBase<Unit, Unit> {
    val loop = ToolLoop(session.messages, session.response, { executor }, session::toolName,
        repeated = { call ->
            session.toolStarted(call.id)
            session.emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
            SessionEntry.ToolResult(call.id, call.name, "This exact call already failed in this turn. Don't repeat it; try something else or answer the user.", isError = true, identity = call.identity)
        },
        onResponse = {
            if (room == null && session.text().isNotEmpty()) session.emit(AgentEvent.TextMessageEnd(session.messageId, session.text()))
        },
        onResult = { result ->
            session.append(result)
            session.emit(AgentEvent.ToolCallResult(result.id, result.output, result.isError, result.identity))
        },
        onFrame = { frame -> if (room == null) when (frame) {
            is StreamFrame.TextDelta -> session.emit(AgentEvent.TextMessageContent(session.messageId, frame.text))
            is StreamFrame.ReasoningDelta -> (frame.summary ?: frame.text)?.let { session.emit(AgentEvent.ThinkingContent(session.messageId, it)) }
            else -> Unit
        } },
        stopped = stopped,
        terminated = { room?.outcome != null },
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
