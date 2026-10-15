package kinuvo.ui.design

import kinuvo.app.session.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme

fun findCompletion(text: String, selection: TextRange, sources: List<CompletionSource>): CompletionQuery? =
    kinuvo.app.session.findCompletion(text, selection.start, selection.end, sources)

@Composable
internal fun CompletionList(query: CompletionQuery, highlighted: Int, onPick: (Completion) -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier.widthIn(max = 420.dp), shape = Theme[shapes][mediumShape], contentPadding = PaddingValues(4.dp)) {
        query.matches.take(MaxShown).forEachIndexed { i, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Theme[shapes][smallShape])
                    .background(if (i == highlighted) color(subtleColor) else color(panelColor))
                    .focusProperties { canFocus = false }
                    .clickable { onPick(item) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${query.source.trigger}${item.value}", fontSize = 13.sp, fontWeight = FontWeight.Normal, singleLine = true)
                if (item.description.isNotBlank()) {
                    Text(item.description, Modifier.weight(1f, fill = false), fontSize = 12.sp, color = color(mutedColor), singleLine = true)
                }
            }
        }
    }
}

internal const val MaxShown = 8
