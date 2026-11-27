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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.TimeSource

/** How the open page's reveal.js deck talks to the app: its [onMessage]s in, [commands] out, and [onStep] to move it. */
class DeckControls(val onMessage: (String) -> Unit, val onStep: (Boolean) -> Unit, val commands: Flow<String>)

/** The presenter's panel beside the deck: where it is, the current slide's notes, the next slide, and big buttons to step. */
@Composable
internal fun DeckRemote(deck: DeckStatus, onStep: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(color(backgroundColor)).padding(Spacing.space12), verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Text("Slide ${deck.number} of ${deck.total}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            PresentTimer()
            Pill("Synced", tone = Tone.Success, icon = Lucide.Radio)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(Radius.menu)).background(color(subtleColor))) {
            if (deck.notes.isEmpty()) Text("No notes for this slide.", Modifier.padding(Spacing.space12), style = TextStyles.callout, color = color(mutedColor))
            else SelectionContainer {
                Markdown(deck.notes, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.space12), markdownTextStyle().copy(fontSize = TextStyles.callout.fontSize))
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

/** Like reveal.js's speaker view: time since the panel opened, which a tap restarts, and the time of day. */
@Composable
private fun PresentTimer() {
    var started by remember { mutableStateOf(TimeSource.Monotonic.markNow()) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000 - now.toEpochMilliseconds() % 1000); now = Clock.System.now() } }
    val elapsed = now.let { started.elapsedNow().inWholeSeconds }
    val clock = now.toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Long) = n.toString().padStart(2, '0')
    val digits = TextStyle(fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        Text(
            (if (elapsed >= 3600) "${elapsed / 3600}:${two(elapsed / 60 % 60)}" else "${elapsed / 60}") + ":${two(elapsed % 60)}",
            Modifier.clip(RoundedCornerShape(Radius.menu)).clickable(role = Role.Button, onClickLabel = "Restart the timer") { started = TimeSource.Monotonic.markNow() }
                .padding(horizontal = Spacing.space4),
            style = digits, fontWeight = FontWeight.SemiBold,
        )
        Text("${two(clock.hour.toLong())}:${two(clock.minute.toLong())}", style = TextStyles.caption.merge(digits), color = color(mutedColor))
    }
}
