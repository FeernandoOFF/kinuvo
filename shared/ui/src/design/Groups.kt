package kinuvo.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.isSpecified
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.ProvideContentColor
import com.composeunstyled.theme.Theme

/** Rows in one rounded card, divided by hairlines, under an optional caption. */
@Composable
fun RowGroup(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { SectionLabel(it, Modifier.padding(start = 6.dp)) }
        val shape = Theme[shapes][largeShape]
        Column(
            Modifier.fillMaxWidth().clip(shape).background(color(borderColor), shape),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            content = content,
        )
    }
}

/** Rounds the first and last of [count] rows of a lazy list so they read as one [RowGroup]. */
fun Modifier.groupItem(index: Int, count: Int): Modifier {
    val r = 16.dp
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (index == 0) r else 0.dp, if (index == 0) r else 0.dp, if (index == count - 1) r else 0.dp, if (index == count - 1) r else 0.dp)
    return clip(shape)
}

/** A row of a [RowGroup]: a title over a muted [subtitle], a [value] at the end and a chevron when it opens something. */
@Composable
fun GroupRow(
    title: String,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = icon?.let { { Icon(it, Modifier.size(18.dp)) } },
    value: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    chevron: Boolean = onClick != null,
    tint: Color = Color.Unspecified,
    titleLines: Int = 1,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> color(selectedControlColor)
        hovered && onClick != null && enabled -> color(selectedControlColor).copy(alpha = 0.5f)
        else -> color(panelColor)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(background).hoverable(interaction)
            .then(if (onClick != null) Modifier.clickable(interaction, Theme[indications][defaultIndication], enabled = enabled, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.let { ProvideContentColor(if (tint.isSpecified) tint else color(mutedColor)) { it() } }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, maxLines = titleLines, overflow = TextOverflow.Ellipsis, color = tint)
            subtitle?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.sp, color = color(mutedColor), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        value?.let { Text(it, Modifier.widthIn(max = 180.dp), fontSize = 13.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis) }
        trailing()
        if (chevron) Icon(Lucide.ChevronRight, Modifier.size(16.dp), tint = color(mutedColor))
    }
}

/** Free-form content as a row of a [RowGroup], e.g. a field. */
@Composable
fun GroupBox(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(color(panelColor)).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** A row that does something right away, e.g. Reconnect; [destructive] ones are red. */
@Composable
fun ActionRow(label: String, icon: ImageVector, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true, subtitle: String? = null) =
    GroupRow(label, onClick, subtitle, icon, enabled = enabled, chevron = false, tint = if (destructive) color(destructiveColor) else Color.Unspecified)

/** An [ActionRow] that asks first: [question] in a sheet on touch screens, a dialog otherwise, confirmed with [confirm]. */
@Composable
fun ConfirmRow(label: String, icon: ImageVector, question: String, onConfirm: () -> Unit, confirm: String = label, destructive: Boolean = true, detail: String? = null, enabled: Boolean = true) {
    var asking by remember { mutableStateOf(false) }
    ActionRow(label, icon, { asking = true }, destructive, enabled)
    ConfirmPopover(asking, question, detail, confirm, destructive, { asking = false }) { asking = false; onConfirm() }
}

/** Asks [question] over the app before [onConfirm]. */
@Composable
fun ConfirmPopover(visible: Boolean, question: String, detail: String?, confirm: String, destructive: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Popover(visible, onDismiss) {
        Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(question, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, color = color(mutedColor), fontSize = 14.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onDismiss, Modifier.weight(1f), style = ButtonStyle.Outlined) { Text("Cancel") }
                if (destructive) Button(onConfirm, Modifier.weight(1f), style = ButtonStyle.Outlined, contentColor = color(destructiveColor)) { Text(confirm) }
                else Button(onConfirm, Modifier.weight(1f), style = ButtonStyle.Primary) { Text(confirm) }
            }
        }
    }
}

/** A few mutually exclusive choices side by side, e.g. Allow, Ask and Block. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true, small: Boolean = false, onSelect: (Int) -> Unit) {
    Row(modifier.clip(CircleShape).background(color(subtleColor)).padding(3.dp).alpha(if (enabled) 1f else 0.5f)) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            Box(
                Modifier.weight(1f, fill = !small).height(if (small) 28.dp else 32.dp).clip(CircleShape).background(if (on) color(panelColor) else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(i) }.semantics { this.selected = on }
                    .padding(horizontal = if (small) 10.dp else 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(option, fontSize = if (small) 12.sp else 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) color(onBackgroundColor) else color(mutedColor), singleLine = true)
            }
        }
    }
}
