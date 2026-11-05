package kinuvo.ui.design

import androidx.compose.ui.input.key.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import androidx.compose.foundation.shape.CircleShape

@Composable
internal fun TitleBarRow(modifier: Modifier = Modifier, leading: Boolean = true, trailing: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val chrome = LocalWindowChrome.current
    Row(
        modifier
            .fillMaxWidth()
            .height(chrome?.height ?: 52.dp)
            .padding(chrome?.padding(leading, trailing) ?: PaddingValues())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
internal fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Hint(label, TooltipSide.Bottom) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            style = ButtonStyle.Ghost,
            shape = if (LocalInteractionMode.current == InteractionMode.Touch) CircleShape else Theme[shapes][mediumShape],
            contentColor = if (LocalInteractionMode.current == InteractionMode.Touch) color(onBackgroundColor) else color(mutedColor),
            modifier = if (LocalInteractionMode.current == InteractionMode.Touch) Modifier.size(44.dp).background(color(subtleColor), CircleShape)
                else Modifier.size(32.dp).titleBarControl(),
        ) {
            Icon(icon, Modifier.size(17.dp), contentDescription = label)
        }
    }
}
