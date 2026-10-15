package kinuvo.ui.design

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState

internal val Bold = SpanStyle(fontWeight = FontWeight.Bold)
internal val Italic = SpanStyle(fontStyle = FontStyle.Italic)

private class InlineRule(val marker: String, val pattern: Regex, val apply: RichTextState.(TextRange) -> Unit)

@OptIn(ExperimentalRichTextApi::class)
private val InlineRules = listOf(
    InlineRule("**", Regex("""(?<!\*)\*\*([^*\n]+)\*\*$""")) { addSpanStyle(Bold, it); removeSpanStyle(Bold) },
    InlineRule("*", Regex("""(?<![*\w])\*([^*\s](?:[^*\n]*[^*\s])?)\*$""")) { addSpanStyle(Italic, it); removeSpanStyle(Italic) },
    InlineRule("_", Regex("""(?<![_\w])_([^_\s](?:[^_\n]*[^_\s])?)_$""")) { addSpanStyle(Italic, it); removeSpanStyle(Italic) },
    InlineRule("`", Regex("""(?<!`)`([^`\n]+)`$""")) { addRichSpan(RichSpanStyle.Code(), it); removeCodeSpan() },
)

/** Markdown markers that may close an inline span when typed. */
internal fun isInlineMarker(c: Char) = c == '*' || c == '_' || c == '`'

/** Turns inline Markdown that was just closed at the cursor (`**bold**`, `*italic*`, `_italic_`, `` `code` ``) into formatting. */
internal fun RichTextState.applyInlineMarkdown(): Boolean {
    if (!selection.collapsed) return false
    val end = selection.start
    val before = annotatedString.text.substring(0, end)
    for (rule in InlineRules) {
        val match = rule.pattern.find(before) ?: continue
        val start = match.range.first
        val length = match.groupValues[1].length
        val marker = rule.marker.length
        removeTextRange(TextRange(end - marker, end))
        removeTextRange(TextRange(start, start + marker))
        selection = TextRange(start + length)
        rule.apply(this, TextRange(start, start + length))
        return true
    }
    return false
}
