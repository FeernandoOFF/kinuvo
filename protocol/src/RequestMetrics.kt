package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class RequestKind { Chat, Reflection, Compaction }

@Serializable
data class RequestMetrics(
    val id: String,
    val at: Long,
    val kind: RequestKind,
    val provider: String,
    val model: String,
    val reasoning: String,
    val latencyMs: Long,
    val firstTokenMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val success: Boolean = true,
    val reasoningTokens: Int? = null,
)
