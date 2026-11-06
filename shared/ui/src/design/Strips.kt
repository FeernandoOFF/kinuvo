package kinuvo.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.*

/**
 * Ongoing things that stay in reach above every screen, such as a live call: one row each, highest priority first.
 * Screens move down by [height] rather than being covered.
 */
class Strips {
    internal val rows: SnapshotStateList<StripRow> = mutableStateListOf()
    val height: Dp get() = StripHeight * rows.size
}

internal class StripRow(val key: String, val priority: Int) {
    var tint by mutableStateOf(Color.Unspecified)
    var onClick by mutableStateOf<(() -> Unit)?>(null)
    var content by mutableStateOf<@Composable RowScope.() -> Unit>({})
}

val LocalStrips = staticCompositionLocalOf { Strips() }

/** Puts [content] in the app's strip while this is composed, on a [tint]ed row; tapping the row runs [onClick]. */
@Composable
fun Strip(key: String, tint: Color, onClick: (() -> Unit)? = null, priority: Int = 0, content: @Composable RowScope.() -> Unit) {
    val strips = LocalStrips.current
    val row = remember(key) { StripRow(key, priority) }
    row.tint = tint; row.onClick = onClick; row.content = content
    DisposableEffect(strips, row) {
        val at = strips.rows.indexOfFirst { it.priority < priority }
        if (at < 0) strips.rows.add(row) else strips.rows.add(at, row)
        onDispose { strips.rows.remove(row) }
    }
}

/** The strip's rows across the top of the window, reaching up behind the status bar. */
@Composable
fun StripHost(strips: Strips, modifier: Modifier = Modifier) {
    AnimatedVisibility(strips.rows.isNotEmpty(), modifier, enter = expandVertically(), exit = shrinkVertically()) {
        Column(Modifier.fillMaxWidth()) {
            strips.rows.forEachIndexed { i, row ->
                val tint = row.tint
                Row(
                    Modifier.fillMaxWidth().background(color(backgroundColor)).background(tint.copy(alpha = 0.22f))
                        .then(if (i == 0) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) else Modifier)
                        .then(row.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .height(StripHeight).padding(horizontal = Spacing.space12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.space10),
                    content = row.content,
                )
            }
        }
    }
}

val StripHeight = 52.dp
