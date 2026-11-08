package kinuvo.harness.core

import kinuvo.protocol.ModelTier

data class ResultLimits(val maxTokens: Int, val maxItems: Int?, val olderShare: Double = 0.25) {
    companion object {
        fun of(tier: ModelTier, context: Int? = null): ResultLimits {
            val tokens = context?.takeIf { it > 0 } ?: 24_576
            return when (tier) {
                ModelTier.Micro -> ResultLimits((tokens * 0.15).toInt().coerceAtLeast(400), 3)
                ModelTier.Small -> ResultLimits((tokens * 0.15).toInt(), 5)
                ModelTier.Mid -> ResultLimits((tokens / 10).coerceAtMost(8_000), 20)
                ModelTier.Frontier, ModelTier.NoTools -> ResultLimits((tokens / 10).coerceAtMost(25_000), null)
            }
        }
    }
}

internal suspend fun String.limitResult(tokens: Int, paging: Boolean, estimate: suspend (String) -> Int): String {
    if (estimate(this) <= tokens) return this
    fun cut(keep: Int): String {
        val head = takeWhole(keep / 2)
        var start = length - (keep - head.length)
        if (start in 1 until length && this[start].isLowSurrogate() && this[start - 1].isHighSurrogate()) start++
        val tail = substring(start)
        val advice = if (paging) "narrow the query or page with offset/limit" else "narrow the query"
        return "$head\n…[${length - head.length - tail.length} chars omitted — $advice]\n$tail"
    }
    var low = 0
    var high = length
    while (low < high) {
        val mid = low + (high - low + 1) / 2
        if (estimate(cut(mid)) <= tokens) low = mid else high = mid - 1
    }
    return cut(low)
}
