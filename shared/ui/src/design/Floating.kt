package kinuvo.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.panelColor

/** Room the floating phone header keeps above a screen's content, which scrolls on under its fade. */
val FloatingClearance = 72.dp

/** Phone header buttons sit in round bubbles, a pill when there are several. */
@Composable
fun Bubble(content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(44.dp).clip(shape).background(color(panelColor), shape).border(1.dp, color(borderColor), shape).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun BubbleButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(onBackgroundColor), modifier = Modifier.size(40.dp)) {
        Icon(icon, Modifier.size(20.dp), contentDescription = label)
    }
}

@Composable
fun FloatingBubble(icon: ImageVector, label: String, onClick: () -> Unit) = Bubble { BubbleButton(icon, label, onClick) }

/** A bubble naming where the user is, e.g. the Agent and its open Session; [label] is read out for it. */
@Composable
fun RowScope.FloatingPill(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, fill: Boolean = false, leading: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.weight(1f, fill = fill).height(44.dp).clip(shape).background(color(panelColor), shape).border(1.dp, color(borderColor), shape)
            .clickable(onClick = onClick).clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { leading() }
        Column(verticalArrangement = Arrangement.Center, content = content)
    }
}
