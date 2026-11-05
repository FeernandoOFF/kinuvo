package kinuvo.ui.design

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.mediumShape
import com.composables.ui.theme.shapes
import com.composeunstyled.LocalContentColor
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.theme.Theme
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import org.jetbrains.compose.resources.Font
import ui.generated.resources.Res
import ui.generated.resources.inter_regular
import ui.generated.resources.inter_semibold
import ui.generated.resources.inter_bold
import ui.generated.resources.inter_italic
import ui.generated.resources.inter_bolditalic
import androidx.compose.ui.text.style.TextDecoration

/** Markdown text block, split so headings, code fences and tables get their own components. */
internal sealed interface MdBlock {
    data class Prose(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
}

private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
private val listItem = Regex("""^\s*([-*+]|\d+[.)])\s""")
private val tableSeparator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

internal fun parseBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val prose = StringBuilder()
    fun flushProse() {
        if (prose.isNotBlank()) blocks += MdBlock.Prose(prose.toString().trim('\n'))
        prose.clear()
    }
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = line.trimStart().takeIf { it.startsWith("```") || it.startsWith("~~~") }?.take(3)
        when {
            fence != null -> {
                flushProse()
                val language = line.trimStart().drop(3).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) code.appendLine(lines[i++])
                blocks += MdBlock.Code(language, code.toString())
            }
            heading.matches(line) -> {
                flushProse()
                val (marks, text) = heading.find(line)!!.destructured
                blocks += MdBlock.Heading(marks.length, text)
            }
            line.trimStart().startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                flushProse()
                val rows = mutableListOf(cells(line))
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++])
                i--
                blocks += MdBlock.Table(rows)
            }
            // Paragraphs are blocks of their own, so a streaming reply parses only the one still growing; lists stay whole.
            line.isBlank() && prose.isNotBlank() && lines.getOrNull(i + 1)?.let { it.isNotBlank() && !it.first().isWhitespace() && !listItem.containsMatchIn(it) } == true -> flushProse()
            else -> prose.appendLine(line)
        }
        i++
    }
    flushProse()
    return blocks
}

private fun cells(row: String) = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/** Inter: message content, and the type scale's small print. */
@Composable
internal fun interFamily() = FontFamily(
    Font(Res.font.inter_regular, FontWeight.Normal),
    Font(Res.font.inter_semibold, FontWeight.SemiBold),
    Font(Res.font.inter_bold, FontWeight.Bold),
    Font(Res.font.inter_italic, FontWeight.Normal, FontStyle.Italic),
    Font(Res.font.inter_bolditalic, FontWeight.Bold, FontStyle.Italic),
)

@Composable
internal fun markdownTextStyle(): TextStyle = LocalTextStyle.current.copy(fontFamily = interFamily(), fontWeight = FontWeight.Normal, lineHeight = 1.5.em)

/** Renders Markdown: prose through compose-rich-editor, fences as [CodeBlock], pipe tables as a grid. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, style: TextStyle = markdownTextStyle()) {
    val blocks = remember(text) { parseBlocks(text) }
    // Paragraphs stay a blank line apart, as in one block of text; other blocks are spaced apart.
    val measurer = rememberTextMeasurer()
    val blankLine = with(LocalDensity.current) { remember(style) { measurer.measure("", style).size.height }.toDp() }
    Column(modifier) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.height(if (block is MdBlock.Prose && blocks[i - 1] is MdBlock.Prose) blankLine else Spacing.space10))
            when (block) {
                is MdBlock.Prose -> RichMarkdown(block.text, style, Modifier.fillMaxWidth())
                // Headings take the UI's serif titles rather than the library's oversized sans.
                is MdBlock.Heading -> RichMarkdown(block.text, (if (block.level == 1) TextStyles.headline else if (block.level == 2) TextStyles.title else TextStyles.callout)
                    .copy(fontWeight = FontWeight.Bold), Modifier.fillMaxWidth().padding(top = if (i > 0) Spacing.space6 else Spacing.none).semantics { heading() })
                is MdBlock.Code -> CodeBlock(block.code, language = block.language)
                is MdBlock.Table -> MarkdownTable(block.rows, style)
            }
        }
    }
}

/** Inline Markdown (bold, links, code spans, lists, headings) via a read-only [RichTextState]. */
@OptIn(ExperimentalRichTextApi::class)
@Composable
fun RichMarkdown(markdown: String, style: TextStyle = markdownTextStyle(), modifier: Modifier = Modifier) {
    val link = color(accentColor)
    val code = color(subtleColor)
    val border = color(borderColor)
    val state = remember { RichTextState() }
    remember(markdown, link, code, border) {
        state.config.linkColor = link
        state.config.linkTextDecoration = TextDecoration.None
        state.config.codeSpanColor = Color.Unspecified
        state.config.codeSpanBackgroundColor = code
        state.config.codeSpanStrokeColor = border
        state.config.codeSpanCornerRadius = 4.sp
        // List items wrap in line with their own text, a short step in from the paragraph.
        state.config.listIndent = 20
        state.config.codeSpanPadding = com.mohamedrejeb.richeditor.model.TextPaddingValues(horizontal = 3.sp, vertical = 0.sp)
        state.setMarkdown(markdown)
    }
    val color = LocalContentColor.current
    BasicRichText(state, modifier, style = style.copy(color = if (style.color == Color.Unspecified) color else style.color))
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, style: TextStyle) {
    val columns = rows.maxOf { it.size }
    // Columns take room by their longest cell, so short ones don't leave long ones wrapping.
    val weights = List(columns) { c -> rows.maxOf { it.getOrElse(c) { "" }.length }.coerceIn(4, 40).toFloat() }
    val border = color(borderColor)
    val shape = Theme[shapes][mediumShape]
    // Every column gets a readable minimum; the rest of the width goes by content. Past the minimums the table scrolls sideways.
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).border(1.dp, border, shape)) {
        val spare = (maxWidth - MinColumnWidth * columns).coerceAtLeast(0.dp)
        val widths = weights.map { MinColumnWidth + spare * (it / weights.sum()) }
        Column(Modifier.horizontalScroll(rememberScrollState(), enabled = spare == 0.dp).width(maxOf(maxWidth, MinColumnWidth * columns))) {
            rows.forEachIndexed { r, row ->
                Row(if (r == 0) Modifier.background(color(subtleColor)) else Modifier) {
                    repeat(columns) { c ->
                        RichMarkdown(
                            row.getOrElse(c) { "" },
                            if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                            Modifier.width(widths[c]).padding(start = Spacing.space10, end = Spacing.space12, top = Spacing.space6, bottom = Spacing.space6),
                        )
                    }
                }
            }
        }
    }
}

private val MinColumnWidth = 88.dp
