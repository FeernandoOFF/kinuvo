package kinuvo.app.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * Markdown source being edited, shaped like Compose's `TextFieldBuffer` so the Composer and a native text view share
 * these edits. Selection ends are exclusive offsets into [text].
 */
interface MarkdownBuffer {
    val text: CharSequence
    val selectionStart: Int
    val selectionEnd: Int
    fun replace(start: Int, end: Int, text: CharSequence)
    fun select(start: Int, end: Int)
}

/** The inline formatting the Composer can toggle, with the markers it types for each. */
val InlineDelimiters: Map<IElementType, String> = mapOf(
    MarkdownElementTypes.STRONG to "**",
    MarkdownElementTypes.EMPH to "*",
    GFMElementTypes.STRIKETHROUGH to "~~",
    MarkdownElementTypes.CODE_SPAN to "`",
)

/** Formatting at the selection: [InlineDelimiters] types around it, and `UNORDERED_LIST` or `ORDERED_LIST` for its list. */
fun MarkdownBuffer.activeTypes(): Set<IElementType> = activeTypes(text, selectionStart, selectionEnd)

fun activeTypes(text: CharSequence, start: Int, end: Int): Set<IElementType> = activeTypes(KinuvoMarkdown.parse(text), start, end)

internal fun activeTypes(tree: ASTNode, start: Int, end: Int): Set<IElementType> {
    val inline = InlineDelimiters.keys.filter { tree.enclosing(it, start, end) != null }
    val list = tree.enclosing(MarkdownElementTypes.LIST_ITEM, start, start)?.parent?.type
    return (inline + listOfNotNull(list)).toSet()
}

/** Removes the [type] formatting around the selection, or wraps the selection in its markers. */
fun MarkdownBuffer.toggle(type: IElementType) {
    val marker = requireNotNull(InlineDelimiters[type]) { "Not an inline type: $type" }
    val start = selectionStart
    val end = selectionEnd
    val node = KinuvoMarkdown.parse(text).enclosing(type, start, end)
    if (node == null) {
        replace(end, end, marker)
        replace(start, start, marker)
        select(start + marker.length, end + marker.length)
        return
    }
    val markers = node.markers()
    val openEnd = node.children.takeWhile { it in markers }.lastOrNull()?.endOffset ?: node.startOffset
    val closeStart = node.children.takeLastWhile { it in markers }.firstOrNull()?.startOffset ?: node.endOffset
    val openLength = openEnd - node.startOffset
    fun moved(offset: Int) = when {
        offset <= node.startOffset -> offset
        offset <= openEnd -> node.startOffset
        offset <= closeStart -> offset - openLength
        else -> closeStart - openLength
    }
    replace(closeStart, node.endOffset, "")
    replace(node.startOffset, openEnd, "")
    select(moved(start), moved(end))
}

/**
 * Makes the selected lines list items, or plain lines when all of them already are items of that kind. Ordered
 * items are numbered from 1.
 */
fun MarkdownBuffer.toggleList(ordered: Boolean) {
    val first = lineStart(selectionStart)
    val last = lineStart(selectionEnd)
    val starts = generateSequence(first) { s -> text.indexOf('\n', s).takeIf { it in s until last }?.plus(1) }.toList()
    val items = starts.map { ListItem.at(text, it) }
    val remove = items.all { it != null && it.ordered == ordered }
    var start = selectionStart
    var end = selectionEnd
    starts.zip(items).withIndex().reversed().forEach { (i, pair) ->
        val (line, item) = pair
        val from = line + (item?.indent ?: 0)
        val to = line + (item?.length ?: 0)
        val marker = when {
            remove -> ""
            ordered -> "${i + 1}. "
            else -> "- "
        }
        replace(from, to, marker)
        val delta = marker.length - (to - from)
        if (end >= to) end += delta else if (end > from) end = from + marker.length
        if (start >= to) start += delta else if (start > from) start = from + marker.length
    }
    select(start, end)
}

/**
 * Continues the list after a line break was just inserted at the cursor: the next item gets the same bullet, the next
 * number or an unchecked task box. A break after an empty item ends the list instead. Returns whether it applied.
 */
fun MarkdownBuffer.continueList(): Boolean {
    val cursor = selectionStart
    if (selectionEnd != cursor || cursor == 0 || text[cursor - 1] != '\n') return false
    val line = lineStart(cursor - 1)
    val item = ListItem.at(text, line) ?: return false
    if (KinuvoMarkdown.parse(text).enclosing(MarkdownElementTypes.CODE_FENCE, line, line) != null) return false
    if (line + item.length == cursor - 1) {
        replace(line, cursor, "")
        select(line, line)
        return true
    }
    val next = " ".repeat(item.indent) + item.next()
    replace(cursor, cursor, next)
    select(cursor + next.length, cursor + next.length)
    return true
}

private fun MarkdownBuffer.lineStart(offset: Int) = text.lastIndexOf('\n', offset - 1) + 1

/** A list item's prefix at a line's start: indentation, marker and the optional task box, [length] characters long. */
private data class ListItem(val indent: Int, val bullet: String?, val number: Int?, val delimiter: Char, val task: Boolean, val length: Int) {
    val ordered get() = number != null
    fun next() = (bullet ?: "${number!! + 1}$delimiter") + " " + if (task) "[ ] " else ""

    companion object {
        private val prefix = Regex("""^( *)(?:([-*+])|(\d{1,9})([.)]))(?: +|$)(\[[ xX]] )?""")

        fun at(text: CharSequence, line: Int): ListItem? {
            val end = text.indexOf('\n', line).let { if (it < 0) text.length else it }
            val match = prefix.find(text.subSequence(line, end)) ?: return null
            val (indent, bullet, number, delimiter, task) = match.destructured
            return ListItem(indent.length, bullet.ifEmpty { null }, number.toIntOrNull(), delimiter.firstOrNull() ?: '.', task.isNotEmpty(), match.value.length)
        }
    }
}

/** The innermost node of [type] spanning [start] to [end]; a cursor at either edge of inline formatting is outside it. */
internal fun ASTNode.enclosing(type: IElementType, start: Int, end: Int): ASTNode? {
    if (start < startOffset || end > endOffset) return null
    if (start == end && type in InlineDelimiters && (start == startOffset || end == endOffset)) return null
    children.firstNotNullOfOrNull { it.enclosing(type, start, end) }?.let { return it }
    return takeIf { it.type == type }
}
