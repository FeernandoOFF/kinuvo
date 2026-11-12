package kinuvo.ui.design

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.ui.components.HorizontalSeparator
import com.composables.ui.theme.borderColor

@Composable
internal fun TableFrame(widths: List<Dp>, modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable (List<Dp>) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier.fillMaxWidth().clip(shape).border(1.dp, color(borderColor), shape)) {
        val minimum = if (compact) widths.map { it.coerceAtMost(80.dp) } else widths
        val total = widths.fold(0.dp) { sum, width -> sum + width }
        val floor = minimum.fold(0.dp) { sum, width -> sum + width }
        val fraction = if (total > floor) ((maxWidth - floor) / (total - floor)).coerceIn(0f, 1f) else 1f
        val fitted = widths.mapIndexed { index, width -> minimum[index] + (width - minimum[index]) * fraction }
        val extra = ((maxWidth - fitted.fold(0.dp) { sum, width -> sum + width }) / widths.size.coerceAtLeast(1)).coerceAtLeast(0.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max)) { content(fitted.map { it + extra }) }
    }
}

@Composable
internal fun TableLine(widths: List<Dp>, separator: Boolean, compact: Boolean = false, cell: @Composable (Int) -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        widths.forEachIndexed { index, width ->
            Box(Modifier.width(width).padding(horizontal = if (compact) 4.dp else 16.dp, vertical = if (compact) 8.dp else 12.dp), contentAlignment = Alignment.CenterStart) { cell(index) }
        }
    }
    if (separator) HorizontalSeparator()
}
