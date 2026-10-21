package kinuvo.harness.core

import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.message.Message
import kinuvo.protocol.RequestKind
import kinuvo.protocol.RequestMetrics
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext

/** Request-local usage and timing, including complete calls collected from provider streams. */
class TokenUsageCapture : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TokenUsageCapture>
    var input: Int? = null
    var output: Int? = null
    var reasoning: Int? = null
    var cached: Int? = null
    var cacheWrite: Int? = null
    var completed: Boolean? = null
    var recordFrame: ((StreamFrame) -> Unit)? = null
}

internal class ModelRequests(
    private val config: AgentConfig,
    private val kind: () -> RequestKind,
    private val save: suspend (RequestMetrics) -> Unit,
) {
    private var measurement: ModelMeasurement? = null

    suspend fun start() {
        val capture = currentCoroutineContext()[TokenUsageCapture] ?: TokenUsageCapture()
        capture.input = null; capture.output = null; capture.reasoning = null; capture.cached = null; capture.cacheWrite = null; capture.completed = null
        measurement = ModelMeasurement(config, kind(), capture)
    }

    fun frame(frame: StreamFrame) { measurement?.frame(frame) }

    fun metadata(response: Message.Assistant) {
        measurement?.capture?.let {
            it.input = it.input ?: response.metaInfo.inputTokensCount
            it.output = it.output ?: response.metaInfo.outputTokensCount
        }
    }

    suspend fun finish(success: Boolean) {
        val current = measurement ?: return
        measurement = null
        save(current.finish(success))
    }
}

internal class ModelMeasurement(private val config: AgentConfig, private val kind: RequestKind, val capture: TokenUsageCapture) {
    private val at = Clock.System.now().toEpochMilliseconds()
    private val start = TimeSource.Monotonic.markNow()
    private var first: Long? = null

    init { capture.recordFrame = ::frame }

    fun frame(frame: StreamFrame) {
        if (first == null && (frame is StreamFrame.TextDelta || frame is StreamFrame.ReasoningDelta || frame is StreamFrame.ToolCallDelta || frame is StreamFrame.ToolCallComplete)) first = start.elapsedNow().inWholeMilliseconds
        if (frame is StreamFrame.End) {
            capture.input = capture.input ?: frame.metaInfo.inputTokensCount
            capture.output = capture.output ?: frame.metaInfo.outputTokensCount
        }
    }

    fun finish(success: Boolean): RequestMetrics {
        capture.recordFrame = null
        return RequestMetrics(Uuid.random().toString(), at, kind, config.provider, config.model, config.reasoning,
            start.elapsedNow().inWholeMilliseconds, first, capture.input, capture.output, capture.cached, capture.cacheWrite, success && capture.completed != false,
            reasoningTokens = capture.reasoning)
    }
}
