package kinuvo.app.session

/** One value the user can insert after a trigger, e.g. a Skill name after `/`. */
data class Completion(val value: String, val description: String = "")

/** Offers [items] for words starting with [trigger]; [startOnly] limits it to the message's first word. */
data class CompletionSource(val trigger: Char, val items: List<Completion>, val startOnly: Boolean = false)

/** The trigger word being typed at the cursor: text[[start], [end]) is replaced on accept. */
data class CompletionQuery(val source: CompletionSource, val start: Int, val end: Int, val query: String) {
    val matches: List<Completion> by lazy {
        val (prefix, rest) = source.items.filter { it.value.contains(query, ignoreCase = true) }
            .partition { it.value.startsWith(query, ignoreCase = true) }
        prefix + rest
    }

    fun insertion(item: Completion) = "${source.trigger}${item.value} "
}

fun findCompletion(text: String, selectionStart: Int, selectionEnd: Int = selectionStart, sources: List<CompletionSource>): CompletionQuery? {
    if (selectionStart != selectionEnd || sources.isEmpty()) return null
    val cursor = selectionStart.coerceIn(0, text.length)
    val start = text.lastIndexOfAny(Whitespace, cursor - 1) + 1
    val end = text.indexOfAny(Whitespace, cursor).let { if (it < 0) text.length else it }
    val word = text.substring(start, cursor)
    val source = sources.firstOrNull { s ->
        word.firstOrNull() == s.trigger && (!s.startOnly || text.substring(0, start).isBlank())
    } ?: return null
    return CompletionQuery(source, start, end, word.drop(1))
}

private val Whitespace = charArrayOf(' ', '\n', '\t')
