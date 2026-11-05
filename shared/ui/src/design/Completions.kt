package kinuvo.ui.design

import kinuvo.app.session.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
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
    Panel(modifier.widthIn(max = 420.dp), shape = Theme[shapes][mediumShape], contentPadding = PaddingValues(Spacing.space4)) {
        query.matches.take(MaxShown).forEachIndexed { i, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Theme[shapes][smallShape])
                    .background(if (i == highlighted) color(subtleColor) else color(panelColor))
                    .focusProperties { canFocus = false }
                    .clickable(role = Role.Button) { onPick(item) }
                    .padding(horizontal = Spacing.space8, vertical = Spacing.space6),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.space10),
            ) {
                Text("${query.source.trigger}${item.value}", style = TextStyles.secondary, fontWeight = FontWeight.Normal, singleLine = true)
                if (item.description.isNotBlank()) {
                    Text(item.description, Modifier.weight(1f, fill = false), style = TextStyles.caption, color = color(mutedColor), singleLine = true)
                }
            }
        }
    }
}

internal const val MaxShown = 8
