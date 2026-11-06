package kinuvo.ui.design

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.composables.ui.theme.*
import kinuvo.app.markdown.MarkdownBuffer
import kinuvo.app.markdown.MarkdownSpan
import kinuvo.app.markdown.isMarker
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes

internal fun TextFieldBuffer.asBuffer(): MarkdownBuffer = object : MarkdownBuffer {
    override val text get() = asCharSequence()
    override val selectionStart get() = selection.min
    override val selectionEnd get() = selection.max
    override fun replace(start: Int, end: Int, text: CharSequence) = this@asBuffer.replace(start, end, text)
    override fun select(start: Int, end: Int) { selection = TextRange(start, end) }
}

internal class MarkdownEditorStyles(private val muted: Color, private val accent: Color, private val code: Color) {
    fun style(span: MarkdownSpan): SpanStyle = when {
        span.isMarker -> SpanStyle(color = muted)
        span.type == MarkdownElementTypes.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
        span.type == MarkdownElementTypes.EMPH -> SpanStyle(fontStyle = FontStyle.Italic)
        span.type == GFMElementTypes.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        span.type == MarkdownElementTypes.CODE_SPAN -> SpanStyle(fontFamily = FontFamily.Monospace, background = code)
        span.type == MarkdownTokenTypes.LIST_BULLET || span.type == MarkdownTokenTypes.LIST_NUMBER -> SpanStyle(color = muted)
        else -> SpanStyle(color = accent)
    }
}

@Composable
internal fun markdownEditorStyles() = MarkdownEditorStyles(color(mutedColor), color(accentColor), color(subtleColor))
