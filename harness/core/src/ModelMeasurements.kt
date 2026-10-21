package kinuvo.harness.core

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.protocol.RequestKind
import kinuvo.protocol.RequestMetrics
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

internal class ModelMeasurement(private val config: AgentConfig, private val kind: RequestKind, val capture: TokenUsageCapture) {
    private val at = Clock.System.now().toEpochMilliseconds()
    private val start = TimeSource.Monotonic.markNow()
    private var first: Long? = null

    fun frame(frame: StreamFrame) {
        if (first == null && (frame is StreamFrame.TextDelta || frame is StreamFrame.ReasoningDelta || frame is StreamFrame.ToolCallDelta || frame is StreamFrame.ToolCallComplete)) first = start.elapsedNow().inWholeMilliseconds
        if (frame is StreamFrame.End) {
            capture.input = capture.input ?: frame.metaInfo.inputTokensCount
            capture.output = capture.output ?: frame.metaInfo.outputTokensCount
        }
    }

    fun finish(success: Boolean) = RequestMetrics(Uuid.random().toString(), at, kind, config.provider, config.model, config.reasoning,
        start.elapsedNow().inWholeMilliseconds, first, capture.input, capture.output, capture.cached, capture.cacheWrite, success && capture.completed != false)
}

fun Flow<StreamFrame>.measured(config: AgentConfig, kind: RequestKind, onMetrics: (RequestMetrics) -> Unit): Flow<StreamFrame> = flow {
    val measurement = ModelMeasurement(config, kind, TokenUsageCapture())
    var success = false
    try {
        this@measured.flowOn(measurement.capture).collect { frame ->
            measurement.frame(frame)
            emit(frame)
        }
        success = true
    } finally { onMetrics(measurement.finish(success)) }
}
