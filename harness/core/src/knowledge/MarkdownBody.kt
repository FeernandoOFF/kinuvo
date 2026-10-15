package kinuvo.harness.core.knowledge

/** [BodyEdit]s on Markdown text, for sources that keep note bodies as Markdown. */
object MarkdownBody {
    fun apply(body: String, edits: List<BodyEdit>, link: (target: String, text: String) -> String = { t, x -> "[[$t|$x]]" }): String =
        edits.fold(body.trim()) { text, e ->
            when (e) {
                is BodyEdit.Append -> append(text, e.markdown.trim(), e.under?.trim())
                is BodyEdit.Link -> link(text, e.text) { link(e.target, it) }
                is BodyEdit.Replace -> replace(text, e.old, e.new)
            }
        }

    private fun append(text: String, markdown: String, under: String?): String {
        if (under == null) return if (text.isEmpty()) markdown else "$text\n\n$markdown"
        val lines = text.lines()
        val headings = mutableMapOf<Int, MatchResult>()
        var fence: String? = null
        lines.forEachIndexed { i, line ->
            val marker = FENCE.matchEntire(line)?.groupValues?.get(1)
            if (fence != null) {
                if (marker != null && marker.first() == fence.first() && marker.length >= fence.length && line.trim() == marker) fence = null
            } else if (marker != null) fence = marker
            else HEADING.matchEntire(line)?.let { headings[i] = it }
        }
        val start = headings.entries.firstOrNull { it.value.groupValues[2].trim().equals(under, true) }?.key ?: -1
        if (start < 0) return (if (text.isEmpty()) "" else "$text\n\n") + "## $under\n\n$markdown"
        val level = headings.getValue(start).groupValues[1].length
        val end = (start + 1 until lines.size).firstOrNull { i ->
            headings[i]?.groupValues?.get(1)?.length?.let { it <= level } == true
        } ?: lines.size
        val section = lines.subList(0, end).dropLastWhile { it.isBlank() }
        // Bullets join the list above them; anything else starts a new paragraph.
        val joined = if (markdown.startsWith("- ") && section.last().trimStart().startsWith("- ")) listOf(markdown) else listOf("", markdown)
        val rest = if (end < lines.size) listOf("") + lines.subList(end, lines.size) else emptyList()
        return (section + joined + rest).joinToString("\n")
    }

    /** Links the first occurrence of [target] that isn't already inside a link, keeping its original casing. */
    private fun link(text: String, target: String, linked: (String) -> String): String {
        require(target.isNotBlank()) { "Give the text to link" }
        val inside = (WIKI.findAll(text) + MD_LINK.findAll(text)).map { it.range }.toList()
        var from = 0
        while (true) {
            val at = text.indexOf(target, from, ignoreCase = true)
            require(at >= 0) { "'$target' isn't in the note's text outside links" }
            val hit = at until at + target.length
            if (inside.none { it.first <= hit.last && hit.first <= it.last }) return text.substring(0, at) + linked(text.substring(hit)) + text.substring(hit.last + 1)
            from = at + target.length
        }
    }

    private fun replace(text: String, old: String, new: String): String {
        require(old.isNotEmpty()) { "Give the text to replace" }
        val count = Regex(Regex.escape(old)).findAll(text).count()
        require(count == 1) { if (count == 0) "The text to replace isn't in the note" else "The text to replace occurs $count times; give more of it" }
        return text.replace(old, new)
    }

    private val HEADING = Regex(""" {0,3}(#{1,6})\s+(.*)""")
    private val FENCE = Regex(""" {0,3}(`{3,}|~{3,}).*""")
    private val WIKI = Regex("""\[\[[^\]]*]]""")
    private val MD_LINK = Regex("""\[[^\]]*]\([^)]*\)""")
}
