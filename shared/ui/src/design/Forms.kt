package kinuvo.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.*

/** A labelled form control; its [hint] opens from a (?) by the label. */
@Composable
internal fun FormField(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = TextStyles.secondary, color = color(mutedColor))
            hint?.let { InfoButton(it) }
        }
        content()
    }
}

/** A labelled control as a row of a [RowGroup]; its [hint] opens from a (?) by the label. */
@Composable
internal fun FieldRow(label: String, hint: String? = null, content: @Composable () -> Unit) = GroupBox {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = TextStyles.secondary, color = color(mutedColor))
        hint?.let { InfoButton(it) }
    }
    content()
}

/** A row of a [RowGroup] showing the picked option, which opens a menu of [options]. */
@Composable
internal fun <T> PickerRow(title: String, options: List<T>, current: T, label: (T) -> String, onSelect: (T) -> Unit, icon: ImageVector? = null,
    subtitle: String? = null, hint: ((T) -> String)? = null) {
    var open by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = {
            DropdownMenuPanel {
                options.forEach { option ->
                    DropdownMenuItem(onClick = { open = false; onSelect(option) }, trailing = if (option == current) ({ Icon(Lucide.Check, Modifier.size(14.dp)) }) else null) {
                        Column {
                            Text(label(option))
                            hint?.invoke(option)?.takeIf { it.isNotEmpty() }?.let { Text(it, style = TextStyles.caption, color = color(mutedColor)) }
                        }
                    }
                }
            }
        },
    ) {
        GroupRow(title, { open = true }, subtitle, icon, value = label(current), chevron = false) {
            Icon(Lucide.ChevronsUpDown, Modifier.size(14.dp), tint = color(mutedColor))
        }
    }
}

/** A setting turned on or off as a row of a [RowGroup]. */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null, enabled: Boolean = true) =
    GroupRow(title, subtitle = subtitle, icon = icon, enabled = enabled, chevron = false, toggled = checked, onToggle = onChange)

/** A (?) showing [text] when tapped: over the app on touch screens, by the button otherwise. */
@Composable
internal fun InfoButton(text: String, header: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val touch = LocalInteractionMode.current == InteractionMode.Touch
    val trigger: @Composable () -> Unit = {
        // In a header, it matches the header's other round buttons.
        IconButton(onClick = { open = !open }, style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(mutedColor),
            modifier = if (header && touch) Modifier.size(44.dp).background(color(subtleColor), CircleShape) else Modifier.size(if (touch) 40.dp else 24.dp)) {
            Icon(Lucide.CircleQuestionMark, Modifier.size(14.dp), contentDescription = "About this")
        }
    }
    if (LocalInteractionMode.current == InteractionMode.Touch && LocalPopovers.current != null) {
        trigger()
        Popover(open, { open = false }) { Text(text, style = TextStyles.label) }
    } else DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        panel = { DropdownMenuPanel { Text(text, Modifier.widthIn(max = 320.dp).padding(horizontal = 10.dp, vertical = 6.dp), style = TextStyles.secondary) } },
    ) { trigger() }
}

/** Multi-line bordered input; [mono] for code-like text such as SKILL.md, which isn't capitalized. */
@Composable
internal fun TextArea(state: TextFieldState, placeholder: String = "", lines: Int = 5, mono: Boolean = false, capitalize: Boolean = !mono, trailing: (@Composable () -> Unit)? = null) {
    val style = if (mono) MonoStyle else inputTextStyle()
    TextField(
        state,
        Modifier.fillMaxWidth(),
        trailing = trailing,
        backgroundColor = color(backgroundColor),
        borderColor = color(borderColor),
        placeholder = { Text(placeholder, style = style) },
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = lines, maxHeightInLines = 30),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        textStyle = style,
        keyboardOptions = inputKeyboard(capitalize),
    )
}

/** A dropdown of [options]; [hint] adds a muted second line to each. */
@Composable
internal fun <T> Picker(options: List<T>, current: T, label: (T) -> String, onSelect: (T) -> Unit, hint: ((T) -> String)? = null, icon: ImageVector? = null, modifier: Modifier = Modifier, stretch: Boolean = false,
    style: ButtonStyle = ButtonStyle.Outlined) {
    var open by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = modifier,
        panel = {
            DropdownMenuPanel {
                options.forEach { option ->
                    DropdownMenuItem(
                        onClick = { open = false; onSelect(option) },
                        trailing = if (option == current) ({ Icon(Lucide.Check, Modifier.size(14.dp)) }) else null,
                    ) {
                        Column {
                            Text(label(option))
                            hint?.invoke(option)?.takeIf { it.isNotEmpty() }?.let { Text(it, style = TextStyles.caption, color = color(mutedColor)) }
                        }
                    }
                }
            }
        },
    ) {
        Button(onClick = { open = true }, modifier = if (stretch) Modifier.fillMaxWidth() else Modifier, style = style, buttonSize = ButtonSize.Small,
            contentPadding = if (style == ButtonStyle.Ghost) PaddingValues(end = 10.dp) else PaddingValues(horizontal = 10.dp)) {
            icon?.let { Icon(it, Modifier.size(14.dp), tint = color(mutedColor)) }
            // Stretched to a given width, the label takes the room and the chevron keeps to the end.
            Text(label(current), if (stretch) Modifier.weight(1f) else Modifier.weight(1f, fill = false).widthIn(max = 180.dp), style = TextStyles.secondary, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
            Icon(Lucide.ChevronDown, Modifier.size(14.dp), tint = color(mutedColor))
        }
    }
}

/** A small button with an icon. */
@Composable
internal fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, singleLine = true)
    }
    if (destructive) Button(onClick = onClick, enabled = enabled, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(destructiveColor), content = content)
    else Button(onClick = onClick, enabled = enabled, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small, content = content)
}

@Composable
internal fun PaneLoading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
