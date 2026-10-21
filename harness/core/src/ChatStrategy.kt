package kinuvo.harness.core

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.requireEndFrame
import ai.koog.serialization.JSONObject
import kinuvo.harness.core.tools.ToolExecutor
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry
import kotlinx.serialization.json.Json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

internal fun chatStrategy(session: RunSession, executor: ToolExecutor, stopped: (String) -> Unit) = strategy<Unit, Unit>("kinuvoChat") {
    val failed = mutableSetOf<Pair<String, String>>()
    var repeats = 0
    var pending = emptyList<SessionEntry.ToolCall>()
    fun checkRepeats() = check(repeats < 2) { "Stopped: the model kept repeating a tool call that failed" }
    val checkHistory by node<Unit, Unit> {
        checkRepeats()
        llm.writeSession { prompt = session.load() }
    }
    val compactHistory by nodeLLMCompressHistory<Unit>(name = COMPACT_HISTORY, strategy = session.compressionStrategy)
    val request by node<Unit, Unit>("streamResponse") {
        llm.writeSession {
            requestLLMStreaming().requireEndFrame().collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> session.emit(AgentEvent.TextMessageContent(session.messageId, frame.text))
                    is StreamFrame.ReasoningDelta -> (frame.summary ?: frame.text)?.let { session.emit(AgentEvent.ThinkingContent(session.messageId, it)) }
                    else -> Unit
                }
            }
            prompt = prompt.withMessages { it + session.responseMessages() }
        }
        if (session.text().isNotEmpty()) session.emit(AgentEvent.TextMessageEnd(session.messageId, session.text()))
        pending = session.calls
    }
    val executeTool by node<Unit, SessionEntry.ToolResult>("executeTool") {
        checkRepeats()
        val call = pending.first()
        val key = call.name to canonical(call.args)
        val result = toolCall(call, session.toolName(call.name)) {
            if (key in failed) {
                repeats++
                session.toolStarted(call.id)
                session.emit(AgentEvent.ToolCallStart(call.id, call.name, call.args, call.identity))
                SessionEntry.ToolResult(call.id, call.name, "This exact call already failed in this turn. Don't repeat it; try something else or answer the user.", isError = true, identity = call.identity)
            } else executor.call(call, stopped = stopped)
        }
        if (result.isError) failed += key
        pending = pending.drop(1)
        result
    }

    edge(nodeStart forwardTo checkHistory)
    edge(checkHistory forwardTo compactHistory onCondition { session.needsCompression(llm.prompt) })
    edge(checkHistory forwardTo request onCondition { !session.needsCompression(llm.prompt) })
    edge(compactHistory forwardTo request)
    edge(request forwardTo nodeFinish onCondition { pending.isEmpty() })
    edge(request forwardTo executeTool onCondition { pending.isNotEmpty() })
    edge(executeTool forwardTo executeTool onCondition { pending.isNotEmpty() } transformed { Unit })
    edge(executeTool forwardTo checkHistory onCondition { pending.isEmpty() } transformed { Unit })
}

@OptIn(InternalAgentsApi::class)
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

private fun canonical(args: String) = runCatching { Json.parseToJsonElement(args).deterministic().toString() }.getOrDefault(args)
