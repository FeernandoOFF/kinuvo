package kinuvo.app.markdown

import org.intellij.markdown.MarkdownElementTypes.ATX_1
import org.intellij.markdown.MarkdownElementTypes.ATX_2
import org.intellij.markdown.MarkdownElementTypes.ATX_3
import org.intellij.markdown.MarkdownElementTypes.ATX_4
import org.intellij.markdown.MarkdownElementTypes.ATX_5
import org.intellij.markdown.MarkdownElementTypes.ATX_6
import org.intellij.markdown.MarkdownElementTypes.AUTOLINK
import org.intellij.markdown.MarkdownElementTypes.BLOCK_QUOTE
import org.intellij.markdown.MarkdownElementTypes.CODE_BLOCK
import org.intellij.markdown.MarkdownElementTypes.CODE_FENCE
import org.intellij.markdown.MarkdownElementTypes.CODE_SPAN
import org.intellij.markdown.MarkdownElementTypes.EMPH
import org.intellij.markdown.MarkdownElementTypes.FULL_REFERENCE_LINK
import org.intellij.markdown.MarkdownElementTypes.IMAGE
import org.intellij.markdown.MarkdownElementTypes.INLINE_LINK
import org.intellij.markdown.MarkdownElementTypes.LINK_DESTINATION
import org.intellij.markdown.MarkdownElementTypes.LINK_LABEL
import org.intellij.markdown.MarkdownElementTypes.LINK_TEXT
import org.intellij.markdown.MarkdownElementTypes.LIST_ITEM
import org.intellij.markdown.MarkdownElementTypes.ORDERED_LIST
import org.intellij.markdown.MarkdownElementTypes.PARAGRAPH
import org.intellij.markdown.MarkdownElementTypes.SETEXT_1
import org.intellij.markdown.MarkdownElementTypes.SETEXT_2
import org.intellij.markdown.MarkdownElementTypes.SHORT_REFERENCE_LINK
import org.intellij.markdown.MarkdownElementTypes.STRONG
import org.intellij.markdown.MarkdownElementTypes.UNORDERED_LIST
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.entities.EntityConverter
import org.intellij.markdown.parser.LinkMap

/** Rendered Markdown values, independent of the parser's AST and source offsets. */
data class MarkdownDocument(val blocks: List<MarkdownBlock>)

sealed class MarkdownBlock {
    data class Heading(val level: Int, val children: List<MarkdownInline>) : MarkdownBlock()
    data class Paragraph(val children: List<MarkdownInline>) : MarkdownBlock()
    data class UnorderedList(val items: List<MarkdownListItem>) : MarkdownBlock()
    data class OrderedList(val start: Int, val items: List<MarkdownListItem>) : MarkdownBlock()
    data class BlockQuote(val blocks: List<MarkdownBlock>) : MarkdownBlock()
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock()
    data class Table(val header: MarkdownTableRow, val rows: List<MarkdownTableRow>, val alignments: List<MarkdownColumnAlignment>) : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
}

data class MarkdownListItem(val blocks: List<MarkdownBlock>, val checked: Boolean? = null)
data class MarkdownTableRow(val cells: List<MarkdownTableCell>)
data class MarkdownTableCell(val children: List<MarkdownInline>)
enum class MarkdownColumnAlignment { None, Left, Center, Right }

sealed class MarkdownInline {
    data class Text(val text: String) : MarkdownInline()
    data class Strong(val children: List<MarkdownInline>) : MarkdownInline()
    data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline()
    data class Strikethrough(val children: List<MarkdownInline>) : MarkdownInline()
    data class CodeSpan(val code: String) : MarkdownInline()
    data class Link(val destination: String, val children: List<MarkdownInline>) : MarkdownInline()
    data class Image(val alt: String, val url: String) : MarkdownInline()
    data object SoftLineBreak : MarkdownInline()
    data object HardLineBreak : MarkdownInline()
}

fun markdownDocument(content: String): MarkdownDocument = DocumentBuilder(content, KinuvoMarkdown.parse(content)).document()

private class DocumentBuilder(val source: String, val root: ASTNode) {
    val links = LinkMap.buildLinkMap(root, source)
    fun ASTNode.text() = source.substring(startOffset, endOffset)
    fun document() = MarkdownDocument(blocks(root))

    fun blocks(node: ASTNode): List<MarkdownBlock> = node.children.mapNotNull { child ->
        val level = when (child.type) {
            ATX_1, SETEXT_1 -> 1
            ATX_2, SETEXT_2 -> 2
            ATX_3 -> 3
            ATX_4 -> 4
            ATX_5 -> 5
            ATX_6 -> 6
            else -> null
        }
        when {
            level != null -> MarkdownBlock.Heading(level, inlines(child.children.filter {
                it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
            }.flatMap { it.children }).trimSpaces())
            else -> when (child.type) {
                PARAGRAPH -> MarkdownBlock.Paragraph(inlines(child.children))
                UNORDERED_LIST -> MarkdownBlock.UnorderedList(items(child))
                ORDERED_LIST -> MarkdownBlock.OrderedList(child.children.firstOrNull { it.type == LIST_ITEM }?.children
                    ?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }?.text()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 1, items(child))
                BLOCK_QUOTE -> MarkdownBlock.BlockQuote(blocks(child))
                CODE_FENCE -> {
                    val lines = child.children.dropWhile { it.type != MarkdownTokenTypes.EOL }.drop(1)
                        .takeWhile { it.type != MarkdownTokenTypes.CODE_FENCE_END }
                    MarkdownBlock.CodeBlock(child.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.text()?.trim(),
                        lines.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.EOL }
                            .joinToString("") { it.text() }.removeSuffix("\n"))
                }
                CODE_BLOCK -> MarkdownBlock.CodeBlock(null, child.children.joinToString("") {
                    if (it.type == MarkdownTokenTypes.CODE_LINE) it.text().let { line -> if (line.startsWith('\t')) line.drop(1) else line.removePrefix("    ") } else it.text()
                })
                GFMElementTypes.TABLE -> table(child)
                MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownBlock.HorizontalRule
                else -> null
            }
        }
    }

    fun items(node: ASTNode) = node.children.filter { it.type == LIST_ITEM }.map { item ->
        MarkdownListItem(blocks(item), item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }?.text()?.let { it[1] != ' ' })
    }

    fun table(node: ASTNode): MarkdownBlock.Table {
        fun row(node: ASTNode) = MarkdownTableRow(node.children.filter { it.type == GFMTokenTypes.CELL }
            .map { MarkdownTableCell(inlines(it.children).trimSpaces()) })
        val header = node.children.first { it.type == GFMElementTypes.HEADER }
        val alignment = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }?.text().orEmpty()
            .trim().removePrefix("|").removeSuffix("|").split('|').map { cell ->
                val value = cell.trim()
                when {
                    value.startsWith(':') && value.endsWith(':') -> MarkdownColumnAlignment.Center
                    value.startsWith(':') -> MarkdownColumnAlignment.Left
                    value.endsWith(':') -> MarkdownColumnAlignment.Right
                    else -> MarkdownColumnAlignment.None
                }
            }
        return MarkdownBlock.Table(row(header), node.children.filter { it.type == GFMElementTypes.ROW }.map(::row), alignment)
    }

    fun inlines(nodes: List<ASTNode>): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        for ((index, node) in nodes.withIndex()) {
            val inline = when (node.type) {
                STRONG -> MarkdownInline.Strong(inlines(node.children.filterNot { it in node.markers() }))
                EMPH -> MarkdownInline.Emphasis(inlines(node.children.filterNot { it in node.markers() }))
                GFMElementTypes.STRIKETHROUGH -> MarkdownInline.Strikethrough(inlines(node.children.filterNot { it.type == GFMTokenTypes.TILDE }))
                CODE_SPAN -> MarkdownInline.CodeSpan(node.codeSpanText(source))
                INLINE_LINK, FULL_REFERENCE_LINK, SHORT_REFERENCE_LINK -> link(node) ?: MarkdownInline.Text(node.text())
                AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK, GFMTokenTypes.GFM_AUTOLINK -> {
                    val label = node.text().removePrefix("<").removeSuffix(">")
                    val destination = when {
                        label.startsWith("www.") -> "http://$label"
                        '@' in label && ":" !in label -> "mailto:$label"
                        else -> label
                    }
                    MarkdownInline.Link(destination, listOf(MarkdownInline.Text(label)))
                }
                IMAGE -> node.children.firstOrNull { it.type == INLINE_LINK || it.type == FULL_REFERENCE_LINK || it.type == SHORT_REFERENCE_LINK }?.let(::link)?.let {
                    MarkdownInline.Image(plainText(it.children), it.destination)
                } ?: MarkdownInline.Text(node.text())
                MarkdownTokenTypes.LT -> if (nodes.getOrNull(index + 1)?.type == MarkdownTokenTypes.EMAIL_AUTOLINK) null else MarkdownInline.Text(node.text())
                MarkdownTokenTypes.GT -> if (nodes.getOrNull(index - 1)?.type == MarkdownTokenTypes.EMAIL_AUTOLINK) null else MarkdownInline.Text(node.text())
                MarkdownTokenTypes.HARD_LINE_BREAK -> MarkdownInline.HardLineBreak
                MarkdownTokenTypes.EOL -> if (nodes.getOrNull(index - 1)?.type == MarkdownTokenTypes.HARD_LINE_BREAK) null else MarkdownInline.SoftLineBreak
                MarkdownTokenTypes.BLOCK_QUOTE -> null
                MarkdownTokenTypes.WHITE_SPACE -> MarkdownInline.Text(if (nodes.getOrNull(index - 1)?.type == MarkdownTokenTypes.BLOCK_QUOTE) node.text().removePrefix(" ") else node.text())
                else -> MarkdownInline.Text(renderText(node.text()))
            }
            if (inline is MarkdownInline.Text && result.lastOrNull() is MarkdownInline.Text) {
                val previous = result.removeAt(result.lastIndex) as MarkdownInline.Text
                result += MarkdownInline.Text(previous.text + inline.text)
            } else if (inline != null) result += inline
        }
        return result.toList()
    }

    fun renderText(text: String): String = Regex("&(amp|lt|gt|quot);").replace(EntityConverter.replaceEntities(text, true, true)) {
        when (it.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            else -> "\""
        }
    }

    fun link(node: ASTNode): MarkdownInline.Link? {
        val label = node.children.firstOrNull { it.type == LINK_TEXT || it.type == LINK_LABEL } ?: return null
        val destination = if (node.type == INLINE_LINK) {
            node.children.firstOrNull { it.type == LINK_DESTINATION }?.text()?.let { LinkMap.normalizeDestination(it, false).toString() }
        } else {
            val reference = node.children.firstOrNull { it.type == LINK_LABEL } ?: label
            links.getLinkInfo(reference.text())?.destination?.toString()
        } ?: return null
        return MarkdownInline.Link(destination, inlines(label.children.drop(1).dropLast(1)))
    }

    fun plainText(inlines: List<MarkdownInline>): String = inlines.joinToString("") {
        when (it) {
            is MarkdownInline.Text -> it.text
            is MarkdownInline.Strong -> plainText(it.children)
            is MarkdownInline.Emphasis -> plainText(it.children)
            is MarkdownInline.Strikethrough -> plainText(it.children)
            is MarkdownInline.CodeSpan -> it.code
            is MarkdownInline.Link -> plainText(it.children)
            is MarkdownInline.Image -> it.alt
            MarkdownInline.SoftLineBreak, MarkdownInline.HardLineBreak -> "\n"
        }
    }
}

private fun List<MarkdownInline>.trimSpaces(): List<MarkdownInline> = mapIndexedNotNull { index, inline ->
    if (inline !is MarkdownInline.Text) inline else {
        val text = inline.text.let { if (index == 0) it.trimStart() else it }.let { if (index == lastIndex) it.trimEnd() else it }
        text.takeIf { it.isNotEmpty() }?.let(MarkdownInline::Text)
    }
}
