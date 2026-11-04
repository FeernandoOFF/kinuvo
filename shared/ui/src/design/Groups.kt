package kinuvo.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.style.TextAlign
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

/** A row of a [RowGroup]: a title over a muted [subtitle], a [value] at the end and a chevron when it opens something; with [onToggle], the whole row is one switch. */
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
    toggled: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.hovered()
    val background = when {
        selected -> color(selectedControlColor)
        hovered && onClick != null && enabled -> color(selectedControlColor).copy(alpha = 0.5f)
        else -> color(panelColor)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(background).hoverable(interaction)
            .then(if (onClick != null) Modifier.clickable(interaction, Theme[indications][defaultIndication], enabled = enabled, onClick = onClick) else Modifier)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .then(if (onToggle != null) Modifier.toggleable(toggled, interaction, Theme[indications][defaultIndication], enabled, Role.Switch, onToggle) else Modifier)
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
        if (onToggle != null && enabled) ToggleSwitch(toggled, null)
        if (chevron) Icon(Lucide.ChevronRight, Modifier.size(16.dp), tint = color(mutedColor))
    }
}

/** An on/off switch; without [onChange] it only shows [checked], e.g. in a row that toggles as a whole. */
@Composable
fun ToggleSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val offset by animateDpAsState(if (checked) 18.dp else 0.dp)
    Box(
        modifier.size(46.dp, 28.dp).clip(CircleShape).background(if (checked) color(accentColor) else color(mutedColor).copy(alpha = 0.35f))
            .then(if (onChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange) else Modifier)
            .alpha(if (enabled) 1f else 0.45f).padding(3.dp),
    ) { Box(Modifier.offset(x = offset).size(22.dp).background(Color.White, CircleShape)) }
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
                if (destructive) DangerButton(confirm, onConfirm, Modifier.weight(1f))
                else Button(onConfirm, Modifier.weight(1f), style = ButtonStyle.Primary) { Text(confirm) }
            }
        }
    }
}

/** A filled button for an action that can't be undone, e.g. Discard or Delete. */
@Composable
fun DangerButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 44.dp).clip(CircleShape).background(color(destructiveColor)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, singleLine = true) }
}

/** A few mutually exclusive choices side by side, e.g. Allow, Ask and Block. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true, small: Boolean = false, onSelect: (Int) -> Unit) {
    Row(modifier.clip(CircleShape).background(color(subtleColor)).padding(3.dp).alpha(if (enabled) 1f else 0.5f)) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            Box(
                Modifier.weight(1f, fill = !small).height(if (small) 28.dp else 40.dp).clip(CircleShape).background(if (on) color(panelColor) else Color.Transparent)
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

/** What a screen or list shows with nothing in it: an [icon], a [title], an optional [body] and [action], centered. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, Modifier.size(28.dp), tint = color(mutedColor))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        body?.let { Text(it, fontSize = 14.sp, color = color(mutedColor), textAlign = TextAlign.Center) }
        action?.let { Box(Modifier.padding(top = 8.dp)) { it() } }
    }
}

/** Asks before unsaved edits are dropped: [onKeep] goes back to them, [onDiscard] drops them; [errors] say why saving failed. */
@Composable
fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier, errors: List<String> = emptyList()) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(color(scrimColor)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onKeep))
        Panel(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 400.dp).consumeTaps().semantics { paneTitle = "Unsaved changes" },
            shape = Theme[shapes][largeShape], contentPadding = PaddingValues(20.dp)) {
            Text("Discard changes?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Your changes have not been saved.", fontSize = 14.sp, color = color(mutedColor))
            errors.forEach { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = color(destructiveColor)) }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onKeep, modifier = Modifier.weight(1f), style = ButtonStyle.Outlined) { Text("Keep editing") }
                DangerButton("Discard", onDiscard, Modifier.weight(1f))
            }
        }
    }
}
