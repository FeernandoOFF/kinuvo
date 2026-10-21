package kinuvo.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.ui.components.*
import com.composables.ui.theme.mediumShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.delay

/** Monospace block with an optional language label and a copy button. */
@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, language: String? = null, maxLines: Int = Int.MAX_VALUE, previewChars: Int? = null) {
    var shown by remember(code, previewChars) { mutableIntStateOf(minOf(code.length, previewChars ?: code.length)) }
    Panel(modifier.fillMaxWidth(), color = color(codeColor), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().background(color(subtleColor).copy(alpha = 0.6f)).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language?.ifBlank { null } ?: "text", Modifier.weight(1f), fontSize = 11.sp, color = color(mutedColor), singleLine = true)
            CopyButton(code)
        }
        SelectionContainer {
            Text(
                code.take(shown).trimEnd('\n'),
                Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                style = MonoStyle,
                color = color(onCodeColor),
                maxLines = maxLines,
            )
        }
        if (shown < code.length) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Showing $shown of ${code.length} characters", Modifier.weight(1f), fontSize = 11.sp, color = color(mutedColor))
                Button(onClick = { shown = minOf(code.length, shown + (previewChars ?: code.length)) }, style = ButtonStyle.Ghost) {
                    Text("Show more", fontSize = 12.sp)
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun CopyButton(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    IconButton(
        onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
        modifier = modifier.size(28.dp),
        style = ButtonStyle.Ghost,
        shape = Theme[shapes][mediumShape],
        contentColor = color(mutedColor),
    ) {
        Icon(if (copied) Lucide.Check else Lucide.Copy, Modifier.size(14.dp), contentDescription = "Copy")
    }
}
