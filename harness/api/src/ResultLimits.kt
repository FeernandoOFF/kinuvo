package kinuvo.harness

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
