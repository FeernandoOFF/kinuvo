package kinuvo.app.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/** Kinuvo's one Markdown dialect: GitHub Flavored Markdown through JetBrains Markdown, for the chat and the Composer alike. */
object KinuvoMarkdown {
    val flavour = GFMFlavourDescriptor()
    val parser = MarkdownParser(flavour)
    fun parse(content: CharSequence): ASTNode = parser.buildMarkdownTreeFromString(content.toString())
}

/** A range of Markdown source typed by the parser's own element or token type, e.g. `STRONG` or the `EMPH` token of its `**`. */
data class MarkdownSpan(val type: IElementType, val start: Int, val end: Int)

/** Syntax around styled text, such as `**` or a link's destination; the Composer dims it. */
val MarkdownSpan.isMarker get() = type in Markers

/** The Composer's styled ranges: inline formatting, links and list markers, then the markers within them. */
fun markdownSpans(content: CharSequence, node: ASTNode = KinuvoMarkdown.parse(content)): List<MarkdownSpan> {
    val styled = mutableListOf<MarkdownSpan>()
    val markers = mutableListOf<MarkdownSpan>()
    fun visit(node: ASTNode) {
        if (node.type in Styled) {
            styled += node.span()
            node.markers().forEach { markers += it.span() }
        }
        node.children.forEach(::visit)
    }
    visit(node)
    return styled + markers
}

private val Styled = setOf(
    MarkdownElementTypes.STRONG, MarkdownElementTypes.EMPH, GFMElementTypes.STRIKETHROUGH, MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK,
    MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER,
)

private val Markers = setOf(
    MarkdownTokenTypes.EMPH, MarkdownTokenTypes.BACKTICK, GFMTokenTypes.TILDE,
    MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET, MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN,
    MarkdownElementTypes.LINK_DESTINATION, MarkdownElementTypes.LINK_TITLE,
)

/** Direct marker children, so a lone `*` in bold text stays text; a link's brackets sit in its `LINK_TEXT`. */
internal fun ASTNode.markers(): List<ASTNode> = children.flatMap { child ->
    if (child.type == MarkdownElementTypes.LINK_TEXT) child.children.filter { it.type in Markers } else listOfNotNull(child.takeIf { it.type in Markers })
}

private fun ASTNode.span() = MarkdownSpan(type, startOffset, endOffset)
