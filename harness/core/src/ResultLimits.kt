package kinuvo.harness.core

import kinuvo.protocol.ModelTier

data class ResultLimits(val maxTokens: Int, val maxItems: Int?, val olderShare: Double = 0.25) {
    companion object {
        fun of(tier: ModelTier, context: Int? = null): ResultLimits {
            val tokens = context?.takeIf { it > 0 } ?: 24_576
            return when (tier) {
                ModelTier.Micro -> ResultLimits((tokens * 0.15).toInt().coerceAtLeast(400), (tokens * 0.15 / 120).toInt().coerceIn(3, 6))
                ModelTier.Small -> ResultLimits((tokens * 0.15).toInt(), (tokens * 0.15 / 150).toInt().coerceIn(5, 10))
                ModelTier.Mid -> ResultLimits((tokens / 10).coerceAtMost(8_000), 20)
                ModelTier.Frontier, ModelTier.NoTools -> ResultLimits((tokens / 10).coerceAtMost(25_000), null)
            }
        }
    }
}

internal suspend fun String.limitResult(tokens: Int, paging: Boolean, estimate: suspend (String) -> Int): String {
    if ((length + 3) / 4 <= tokens / 2) return this
    val size = estimate(this)
    if (size <= tokens) return this
    fun cut(keep: Int): String {
        val head = takeWhole(keep / 2)
        var start = length - (keep - head.length)
        if (start in 1 until length && this[start].isLowSurrogate() && this[start - 1].isHighSurrogate()) start++
        val tail = substring(start)
        val advice = if (paging) "narrow the query or page with offset/limit" else "narrow the query"
        return "$head\n…[${length - head.length - tail.length} chars omitted — $advice]\n$tail"
    }
    // A few estimates at most: the provider's counter can be slow (Apple's runs a process).
    var keep = (length.toLong() * tokens / size * 9 / 10).toInt().coerceIn(0, length)
    repeat(3) {
        val text = cut(keep)
        if (estimate(text) <= tokens) return text
        keep = keep * 4 / 5
    }
    return cut(keep)
}
