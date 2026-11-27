package kinuvo.ui.artifacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.artifacts.DeckStatus
import kinuvo.ui.design.*
import kotlinx.coroutines.flow.Flow

/** How the open page's reveal.js deck talks to the app: its [onMessage]s in, [commands] out, and [onStep] to move it. */
class DeckControls(val onMessage: (String) -> Unit, val onStep: (Boolean) -> Unit, val commands: Flow<String>)

/** The presenter's panel beside the deck: where it is, the current slide's notes, the next slide, and big buttons to step. */
@Composable
internal fun DeckRemote(deck: DeckStatus, onStep: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(color(backgroundColor)).padding(Spacing.space12), verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Text("Slide ${deck.number} of ${deck.total}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Pill("Synced", tone = Tone.Success, icon = Lucide.Radio)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(Radius.menu)).background(color(subtleColor))) {
            SelectionContainer {
                Text(
                    deck.notes.ifEmpty { "No notes for this slide." },
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.space12),
                    style = TextStyles.callout,
                    color = color(if (deck.notes.isEmpty()) mutedColor else onBackgroundColor),
                )
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
            Text("Next", style = TextStyles.overline, color = color(mutedColor))
            val next = deck.next
            if (next == null) Text("Last slide", style = TextStyles.secondary, color = color(mutedColor))
            else {
                if (next.title.isNotEmpty()) Text(next.title, style = TextStyles.secondary, fontWeight = FontWeight.SemiBold, singleLine = true, overflow = TextOverflow.Ellipsis)
                if (next.text.isNotEmpty()) Text(next.text, style = TextStyles.caption, color = color(mutedColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (next.title.isEmpty() && next.text.isEmpty()) Text("Untitled slide", style = TextStyles.secondary, color = color(mutedColor))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Button(onClick = { onStep(false) }, style = ButtonStyle.Outlined, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                Icon(Lucide.ChevronLeft, Modifier.size(20.dp), contentDescription = null)
                Text("Previous")
            }
            Button(onClick = { onStep(true) }, style = ButtonStyle.Primary, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                Text("Next")
                Icon(Lucide.ChevronRight, Modifier.size(20.dp), contentDescription = null)
            }
        }
    }
}
