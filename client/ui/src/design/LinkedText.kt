package kinuvo.ui.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private val UrlPattern = Regex("""https?://[^\s<>"')\]]+""")

/** [text] with its web addresses tappable; long ones show as their host, so a sign-in link doesn't fill the card. */
@Composable
fun LinkedText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val linked = remember(text) {
        buildAnnotatedString {
            var at = 0
            UrlPattern.findAll(text).forEach { match ->
                val url = match.value.trimEnd('.', ',', ';', ':')
                append(text.substring(at, match.range.first))
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)))) {
                    append(if (url.length > 48) shortLink(url) else url)
                }
                at = match.range.first + url.length
            }
            append(text.substring(at))
        }
    }
    BasicText(linked, modifier, style.copy(color = color))
}

/** `https://auth.example.com/oauth/authorize?…` → `auth.example.com/…`. */
internal fun shortLink(url: String): String {
    val rest = url.substringAfter("://")
    val host = rest.substringBefore('/').substringBefore('?')
    return if (rest.length > host.length) "$host/…" else host
}
