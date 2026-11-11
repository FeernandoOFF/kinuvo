package kinuvo.harness.builtin

import kinuvo.harness.ResultLimits

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
