package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.protocol.RequestKind
import kinuvo.protocol.RequestMetrics
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** A request-local sink for usage fields that Koog's End frame doesn't preserve. */
class TokenUsageCapture : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TokenUsageCapture>
    var input: Int? = null
    var output: Int? = null
    var cached: Int? = null
    var cacheWrite: Int? = null
    var completed: Boolean? = null
}

fun Model.measured(config: AgentConfig, kind: RequestKind, instructions: String, history: List<SessionEntry>,
    tools: List<ToolDescriptor>, onMetrics: (RequestMetrics) -> Unit): Flow<StreamFrame> = flow {
    val capture = TokenUsageCapture()
    val at = Clock.System.now().toEpochMilliseconds()
    val start = TimeSource.Monotonic.markNow()
    var first: Long? = null
    var success = false
    try {
        stream(instructions, history, tools).flowOn(capture).collect { frame ->
            if (first == null && (frame is StreamFrame.TextDelta || frame is StreamFrame.ReasoningDelta || frame is StreamFrame.ToolCallDelta || frame is StreamFrame.ToolCallComplete)) first = start.elapsedNow().inWholeMilliseconds
            if (frame is StreamFrame.End) {
                capture.input = capture.input ?: frame.metaInfo.inputTokensCount
                capture.output = capture.output ?: frame.metaInfo.outputTokensCount
            }
            emit(frame)
        }
        success = capture.completed ?: true
    } finally {
        onMetrics(RequestMetrics(Uuid.random().toString(), at, kind, config.provider, config.model, config.reasoning,
            start.elapsedNow().inWholeMilliseconds, first, capture.input, capture.output, capture.cached, capture.cacheWrite, success))
    }
}
