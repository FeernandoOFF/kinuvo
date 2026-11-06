package kinuvo.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.ui.theme.*
import com.composeunstyled.LocalContentColor
import com.composeunstyled.LocalTextStyle
import com.mikepenz.markdown.compose.Markdown as RenderMarkdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.elements.MarkdownCheckBox
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter.TextPaddingValues
import com.mikepenz.markdown.model.*
import kinuvo.app.markdown.KinuvoMarkdown
import org.intellij.markdown.MarkdownElementTypes.CODE_SPAN
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.jetbrains.compose.resources.Font
import ui.generated.resources.*

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

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, style: TextStyle = markdownTextStyle()) {
    MarkdownContent(text, modifier, style)
}

@Composable
internal fun StreamingMarkdown(text: String, modifier: Modifier = Modifier) {
    MarkdownContent(text, modifier, markdownTextStyle(), streaming = true)
}

@Composable
private fun MarkdownContent(text: String, modifier: Modifier, style: TextStyle, streaming: Boolean = false) {
    val accent = color(accentColor)
    val border = color(borderColor)
    val colors = DefaultMarkdownColors(
        text = style.color.takeUnless { it == Color.Unspecified } ?: LocalContentColor.current,
        codeBackground = color(codeColor), inlineCodeBackground = color(subtleColor),
        dividerColor = border, tableBackground = color(subtleColor),
        highlightText = accent, highlightBackground = accent.copy(alpha = 0.15f),
    )
    val heading = TextStyles.callout.copy(fontWeight = FontWeight.Bold)
    val typography = DefaultMarkdownTypography(
        h1 = TextStyles.headline.copy(fontWeight = FontWeight.Bold),
        h2 = TextStyles.title.copy(fontWeight = FontWeight.Bold),
        h3 = heading, h4 = heading, h5 = heading, h6 = heading,
        text = style, paragraph = style, list = style, bullet = style, ordered = style, table = style, quote = style,
        code = style.copy(fontFamily = FontFamily.Monospace), inlineCode = style.copy(fontFamily = FontFamily.Monospace),
        textLink = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.None)),
    )
    val annotator = markdownAnnotator { content, node ->
        if (node.type == CODE_SPAN) {
            val raw = content.substring(node.children.first().endOffset, node.children.last().startOffset).replace('\n', ' ')
            val code = if (raw.startsWith(' ') && raw.endsWith(' ') && raw.any { it != ' ' }) raw.drop(1).dropLast(1) else raw
            withStyle(typography.inlineCode.copy(background = colors.inlineCodeBackground).toSpanStyle()) { append(code) }
            true
        } else false
    }
    val components = markdownComponents(
        table = { MarkdownTable(it.content, it.node, it.typography.table) },
        checkbox = { model ->
            MarkdownCheckBox(model.content, model.node, model.typography.list) { checked, indicator ->
                val ink = if (checked) accent else colors.text
                Canvas(indicator.padding(top = 3.dp, end = 8.dp).size(16.dp)) {
                    drawRoundRect(ink, cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    if (checked) {
                        drawLine(ink, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .43f, size.height * .73f), 1.5.dp.toPx())
                        drawLine(ink, Offset(size.width * .43f, size.height * .73f), Offset(size.width * .8f, size.height * .25f), 1.5.dp.toPx())
                    }
                }
            }
        },
        codeFence = { MarkdownCodeFence(it.content, it.node) { code, language, _ -> CodeBlock(code, language = language.orEmpty()) } },
        codeBlock = { MarkdownCodeBlock(it.content, it.node) { code, language, _ -> CodeBlock(code, language = language.orEmpty()) } },
    )
    val padding = markdownPadding(block = 10.dp, list = 0.dp, listIndent = 20.dp, listItemTop = 2.dp, listItemBottom = 2.dp)
    val dimens = markdownDimens(tableCellPadding = 8.dp, tableCellWidth = 120.dp)
    val spans = markdownExtendedSpans {
        remember(border) { ExtendedSpans(RoundedCornerSpanPainter(cornerRadius = 4.sp,
            stroke = RoundedCornerSpanPainter.Stroke(border), padding = TextPaddingValues(horizontal = 3.sp, vertical = 0.sp))) }
    }
    if (streaming) {
        // Replies grow by appending; anything else, e.g. an edited reply, starts a fresh state.
        var generation by remember { mutableIntStateOf(0) }
        key(generation) {
            val state = rememberStreamingMarkdownState(flavour = KinuvoMarkdown.flavour)
            LaunchedEffect(text) {
                if (text.startsWith(state.content)) state.append(text.substring(state.content.length)) else generation++
            }
            RenderMarkdown(state, colors, typography, modifier, padding = padding, dimens = dimens, components = components, annotator = annotator, extendedSpans = spans)
        }
    } else {
        RenderMarkdown(text, colors, typography, modifier, flavour = KinuvoMarkdown.flavour, parser = KinuvoMarkdown.parser,
            immediate = true, retainState = true, padding = padding, dimens = dimens, components = components, annotator = annotator, extendedSpans = spans)
    }
}

@Composable
private fun MarkdownTable(content: String, node: ASTNode, style: TextStyle) {
    val rows = remember(node) { node.children.filter { it.type == HEADER || it.type == ROW }.map { row -> row.children.filter { it.type == CELL } } }
    val settings = annotatorSettings()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(node, content, style, density) { (0 until (rows.maxOfOrNull { it.size } ?: 0)).map { column ->
        val pixels = rows.mapIndexedNotNull { index, cells -> cells.getOrNull(column)?.let { cell ->
            val cellStyle = if (index == 0) style.copy(fontWeight = FontWeight.Bold) else style
            val text = buildAnnotatedString { buildMarkdownAnnotatedString(content, cell, settings) }
            measurer.measure(text, cellStyle, softWrap = false).size.width
        } }.maxOrNull() ?: 0
        with(density) { pixels.toDp() }.plus(16.dp).coerceIn(72.dp, 240.dp)
    } }
    BoxWithConstraints(Modifier.fillMaxWidth().background(LocalMarkdownColors.current.tableBackground, RoundedCornerShape(4.dp))) {
        val extra = ((maxWidth - widths.fold(0.dp) { sum, width -> sum + width }) / widths.size.coerceAtLeast(1)).coerceAtLeast(0.dp)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, cells ->
                Row {
                    cells.forEachIndexed { column, cell ->
                        Box(Modifier.width(widths[column] + extra).padding(8.dp)) {
                            MarkdownTableBasicText(content, cell, if (index == 0) style.copy(fontWeight = FontWeight.Bold) else style,
                                maxLines = Int.MAX_VALUE, annotatorSettings = settings)
                        }
                    }
                }
                if (index == 0) MarkdownDivider()
            }
        }
    }
}
