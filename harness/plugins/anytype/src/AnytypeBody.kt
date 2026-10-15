package kinuvo.harness.anytype

import kotlinx.serialization.json.*

/**
 * Anytype blocks as a Note's Markdown body and [kinuvo.harness.core.knowledge.BodyEdit]s as block changes. Mentions and
 * object links read `[[id|label]]`; `[[id|label]]` in new text becomes a mention.
 */
internal object AnytypeBody {
    fun render(blocks: List<JsonObject>, names: Map<String, String> = emptyMap()): String {
        val out = StringBuilder()
        var lastListed = false
        // Nesting under layout rows, columns and other blocks without text doesn't indent.
        val parents = ArrayDeque<Pair<Int, Boolean>>()
        for (b in blocks) {
            while (parents.lastOrNull()?.let { it.first >= b.indent } == true) parents.removeLast()
            val line = line(b, names)
            parents.addLast(b.indent to (line != null))
            if (line == null) continue
            val indent = if (level(b) != null) 0 else parents.count { it.second } - 1
            val listed = b.type in LISTED || indent > 0
            if (out.isNotEmpty()) out.append(if (listed && lastListed) "\n" else "\n\n")
            out.append(line.lines().joinToString("\n") { "  ".repeat(indent) + it })
            lastListed = listed
        }
        return out.toString()
    }

    /** Block text with mentions and object links as `[[id|label]]`. */
    fun canonical(text: String) = text
        .replace(MENTION) { "[[${it.groupValues[1]}|${it.groupValues[2]}]]" }
        .replace(OBJECT_LINK) { "[[${it.groupValues[2]}|${it.groupValues[1]}]]" }

    /** `[[id|label]]` links as mentions, except those in [keep], which map back to how the block wrote them. */
    fun native(markdown: String, keep: Map<String, String> = emptyMap()) =
        markdown.replace(WIKI) { keep[it.value] ?: mention(it.groupValues[1], it.groupValues[2].ifEmpty { it.groupValues[1] }) }

    /** Where to insert at the end of the section headed [under]: `after` its last block or `inside` a toggle heading; null when missing. */
    fun section(blocks: List<JsonObject>, under: String): Pair<String, String>? {
        val i = blocks.indexOfFirst { level(it) != null && plain(it.text).equals(under.trim(), true) }
        if (i < 0) return null
        val heading = blocks[i]
        if (heading.type.startsWith("toggle_heading")) return "inside" to heading.id
        val level = level(heading)!!
        val end = (i + 1 until blocks.size).firstOrNull { j ->
            val b = blocks[j]
            b.indent < heading.indent || b.indent == heading.indent && level(b)?.let { it <= level } == true
        } ?: blocks.size
        val last = (i + 1 until end).lastOrNull { blocks[it].indent == heading.indent && line(blocks[it], emptyMap()) != null }
        return "after" to blocks[last ?: i].id
    }

    /** The first block with [text] outside links, and its text with that occurrence as a mention of [target]. */
    fun link(blocks: List<JsonObject>, text: String, target: String): Pair<JsonObject, String> {
        require(text.isNotBlank()) { "Give the text to link" }
        for (b in blocks.filter { it.type in TEXT }) {
            val raw = b.text
            val links = (MENTION.findAll(raw) + MD_LINK.findAll(raw)).map { it.range }.toList()
            var from = 0
            while (true) {
                val at = raw.indexOf(text, from, ignoreCase = true)
                if (at < 0) break
                val hit = at until at + text.length
                if (links.none { it.first <= hit.last && hit.first <= it.last }) {
                    return b to raw.substring(0, at) + mention(target, raw.substring(hit)) + raw.substring(hit.last + 1)
                }
                from = at + text.length
            }
        }
        throw IllegalArgumentException("'$text' isn't in the note's text outside links")
    }

    /** The one block containing [old] once, and its text with [old] replaced by [new]. */
    fun replace(blocks: List<JsonObject>, old: String, new: String): Pair<JsonObject, String> {
        require(old.isNotEmpty()) { "Give the text to replace" }
        val hits = blocks.filter { it.type in TEXT || it.type == "code" }.map { it to canonical(it.text) }.filter { old in it.second }
        require(hits.isNotEmpty()) {
            if (old in render(blocks)) "The text to replace spans several blocks; replace within one paragraph or item at a time"
            else "The text to replace isn't in the note"
        }
        require(hits.size == 1) { "The text to replace is in ${hits.size} blocks; give more of it" }
        val (block, text) = hits.single()
        val count = Regex(Regex.escape(old)).findAll(text).count()
        require(count == 1) { "The text to replace occurs $count times; give more of it" }
        val keep = (MENTION.findAll(block.text) + OBJECT_LINK.findAll(block.text)).associate { canonical(it.value) to it.value }
        return block to native(text.replace(old, new), keep)
    }

    private fun line(b: JsonObject, names: Map<String, String>): String? {
        val text = canonical(b.text)
        val objectId = b["object_id"]?.jsonPrimitive?.contentOrNull
        return when (b.type) {
            "paragraph" -> text.ifBlank { null }
            "bulleted_list_item", "toggle" -> "- $text"
            "numbered_list_item" -> "1. $text"
            "checkbox" -> "- [${if (b["checked"]?.jsonPrimitive?.booleanOrNull == true) "x" else " "}] $text"
            "quote", "callout" -> text.ifBlank { null }?.lines()?.joinToString("\n") { "> $it" }
            "code" -> "```${b["language"]?.jsonPrimitive?.contentOrNull.orEmpty()}\n${b.text}\n```"
            "embed", "equation" -> "```${b["processor"]?.jsonPrimitive?.contentOrNull ?: "latex"}\n${b.text}\n```"
            "divider" -> "---"
            "table" -> table(b)
            "bookmark" -> b["url"]?.jsonPrimitive?.contentOrNull
            "link" -> objectId?.let { "[[$it|${names[it] ?: it}]]" }
            "file", "image", "video", "audio", "pdf" -> objectId?.let { "[[$it|${b["name"]?.jsonPrimitive?.contentOrNull ?: b.type}]] (${b.type})" }
            else -> level(b)?.let { level -> text.ifBlank { null }?.let { "#".repeat(level) + " " + it } }
        }
    }

    private fun table(b: JsonObject): String? {
        val rows = b["rows"]?.jsonArray.orEmpty().map { row ->
            row.jsonObject["cells"]?.jsonArray.orEmpty().map { cell ->
                when (cell) {
                    is JsonPrimitive -> cell.contentOrNull.orEmpty()
                    is JsonObject -> cell.text
                    is JsonArray -> cell.joinToString(" ") { (it as? JsonObject)?.text.orEmpty() }
                }.let(::canonical).replace("\n", " ").replace("|", "\\|")
            }
        }.takeIf { it.isNotEmpty() } ?: return null
        val width = rows.maxOf { it.size }
        val lines = rows.map { r -> (r + List(width - r.size) { "" }).joinToString(" | ", "| ", " |") }
        return (listOf(lines.first(), List(width) { "---" }.joinToString(" | ", "| ", " |")) + lines.drop(1)).joinToString("\n")
    }

    private fun level(b: JsonObject) = when (b.type) {
        "heading_1", "toggle_heading_1" -> 1
        "heading_2", "toggle_heading_2" -> 2
        "heading_3", "toggle_heading_3" -> 3
        "heading_4", "header_4" -> 4
        else -> null
    }

    private fun plain(text: String) = canonical(text).replace(WIKI) { it.groupValues[2].ifEmpty { it.groupValues[1] } }.replace(Regex("[*_~`]"), "").trim()

    private fun mention(id: String, label: String) = "<mention object_id=\"$id\">$label</mention>"

    private val JsonObject.type get() = this["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    private val JsonObject.text get() = this["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    private val JsonObject.indent get() = this["indent"]?.jsonPrimitive?.intOrNull ?: 0
    private val JsonObject.id get() = getValue("id").jsonPrimitive.content

    private val LISTED = setOf("bulleted_list_item", "numbered_list_item", "checkbox", "toggle")
    private val TEXT = setOf(
        "paragraph", "quote", "toggle", "bulleted_list_item", "numbered_list_item", "checkbox", "callout", "heading_1", "heading_2",
        "heading_3", "heading_4", "header_4", "toggle_heading_1", "toggle_heading_2", "toggle_heading_3",
    )
    private val MENTION = Regex("""<mention object_id="([^"]+)">(.*?)</mention>""")
    private val OBJECT_LINK = Regex("""\[([^\]]*)]\(anytype://object\?objectId=([^&)\s]+)[^)]*\)""")
    private val MD_LINK = Regex("""\[[^\]]*]\([^)]*\)""")
    private val WIKI = Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]""")
}
