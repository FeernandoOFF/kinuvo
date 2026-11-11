package kinuvo.plugins.caldav

/** An XML element by local name (namespace prefixes dropped), enough to read WebDAV multistatus responses. */
internal class Xml(val name: String, val attributes: Map<String, String>, val children: List<Xml>, val text: String) {
    fun child(name: String) = children.firstOrNull { it.name == name }

    /** Elements named [name] at any depth below this one. */
    fun all(name: String): List<Xml> = children.flatMap { (if (it.name == name) listOf(it) else emptyList()) + it.all(name) }

    companion object {
        fun parse(text: String): Xml = Parser(text).document()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Xml {
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                when {
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<!", i) -> skipPast(">")
                    s.startsWith("<", i) -> return element()
                    else -> error("Not an XML document")
                }
            }
        }

        private fun element(): Xml {
            i++
            val end = s.indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '/', '>'), i)
            check(end >= 0) { "Unclosed XML element" }
            val name = s.substring(i, end).substringAfter(':')
            val close = tagEnd(end)
            check(close >= 0) { "Unclosed <$name>" }
            val attributes = ATTRIBUTE.findAll(s.substring(end, close))
                .associate { it.groupValues[1].substringAfter(':') to decode(it.groupValues[2].ifEmpty { it.groupValues[3] }) }
            i = close + 1
            if (s[close - 1] == '/') return Xml(name, attributes, emptyList(), "")
            val children = mutableListOf<Xml>()
            val text = StringBuilder()
            while (true) {
                check(i < s.length) { "Unclosed <$name>" }
                when {
                    s.startsWith("</", i) -> { skipPast(">"); return Xml(name, attributes, children, text.toString()) }
                    s.startsWith("<![CDATA[", i) -> {
                        val e = s.indexOf("]]>", i)
                        check(e >= 0) { "Unclosed CDATA" }
                        text.append(s, i + 9, e)
                        i = e + 3
                    }
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<?", i) -> skipPast("?>")
                    s[i] == '<' -> children += element()
                    else -> {
                        val e = s.indexOf('<', i).let { if (it < 0) s.length else it }
                        text.append(decode(s.substring(i, e)))
                        i = e
                    }
                }
            }
        }

        /** The `>` closing a tag, past any in quoted attribute values. */
        private fun tagEnd(from: Int): Int {
            var quote: Char? = null
            for (j in from until s.length) {
                val c = s[j]
                if (quote != null) { if (c == quote) quote = null }
                else if (c == '"' || c == '\'') quote = c
                else if (c == '>') return j
            }
            return -1
        }

        private fun skipPast(end: String) {
            val e = s.indexOf(end, i)
            check(e >= 0) { "Unclosed XML" }
            i = e + end.length
        }
    }
}

private val ATTRIBUTE = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-z]+);")
private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

private fun decode(text: String) = ENTITY.replace(text) {
    val e = it.groupValues[1]
    when {
        e.startsWith("#x") -> codePoint(e.drop(2).toIntOrNull(16)) ?: it.value
        e.startsWith("#") -> codePoint(e.drop(1).toIntOrNull()) ?: it.value
        else -> ENTITIES[e] ?: it.value
    }
}

private fun codePoint(c: Int?) = if (c == null || c > 0x10FFFF) null else if (c < 0x10000) c.toChar().toString()
    else charArrayOf(Char(0xD800 + ((c - 0x10000) shr 10)), Char(0xDC00 + ((c - 0x10000) and 0x3FF))).concatToString()
