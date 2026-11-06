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
internal fun TableFrame(widths: List<Dp>, modifier: Modifier = Modifier, content: @Composable (List<Dp>) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier.fillMaxWidth().clip(shape).border(1.dp, color(borderColor), shape)) {
        val extra = ((maxWidth - widths.fold(0.dp) { sum, width -> sum + width }) / widths.size.coerceAtLeast(1)).coerceAtLeast(0.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max)) { content(widths.map { it + extra }) }
    }
}

@Composable
internal fun TableLine(widths: List<Dp>, separator: Boolean, cell: @Composable (Int) -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        widths.forEachIndexed { index, width ->
            Box(Modifier.width(width).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) { cell(index) }
        }
    }
    if (separator) HorizontalSeparator()
}
