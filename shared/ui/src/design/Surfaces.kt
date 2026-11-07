package kinuvo.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.composeunstyled.LocalTextStyle
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.LoaderCircle
import com.composables.icons.lucide.Lucide
import com.composables.ui.components.Icon
import com.composables.ui.components.Tooltip
import com.composables.ui.components.TooltipPanel
import com.composables.ui.components.TooltipSide
import com.composables.ui.components.Text
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import com.composeunstyled.ProvideContentColor
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.Role

/** A bordered container for cards, menus and code. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = color(panelColor),
    borderColor: Color = color(com.composables.ui.theme.borderColor),
    shape: Shape = Theme[shapes][mediumShape],
    contentPadding: PaddingValues = PaddingValues(Spacing.space12),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(color, shape)
            .then(if (borderColor.alpha > 0f) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .padding(contentPadding),
    ) { content() }
}

enum class Tone { Neutral, Accent, Warning, Danger, Success }

@Composable
fun Tone.color(): Color = when (this) {
    Tone.Neutral -> color(mutedColor)
    Tone.Accent -> color(accentColor)
    Tone.Warning -> color(warningColor)
    Tone.Danger -> color(destructiveColor)
    Tone.Success -> color(successColor)
}

/** Small rounded status label, e.g. `offline`; [loading] shows a spinner in place of [icon]. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, icon: ImageVector? = null, loading: Boolean = false) {
    val c = tone.color()
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.menu))
            .background(c.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.space8, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.space4),
    ) {
        if (loading) Spinner(size = 12.dp, tint = c) else icon?.let { Icon(it, Modifier.size(12.dp), tint = c) }
        Text(text, Modifier.weight(1f, fill = false), style = TextStyles.caption, lineHeight = 16.sp, fontWeight = FontWeight.Normal, color = c)
    }
}

/** A compact tag beside a title, e.g. `Task`. */
@Composable
fun Badge(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val c = color(mutedColor)
    Row(
        modifier.clip(PillShape).border(1.dp, color(borderColor), PillShape).padding(horizontal = Spacing.space6, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        icon?.let { Icon(it, Modifier.size(10.dp), tint = c) }
        Text(text, style = TextStyles.overline, lineHeight = 14.sp, color = c, singleLine = true)
    }
}

/** An error message that remains readable at phone widths. */
@Composable
fun ErrorMessage(text: String, modifier: Modifier = Modifier) {
    if (text == "This action requires management access") {
        ManagementAccessRequired()
        return
    }
    val c = Tone.Danger.color()
    Row(
        modifier.fillMaxWidth().clip(Theme[shapes][mediumShape]).background(c.copy(alpha = 0.12f)).padding(Spacing.space12),
        horizontalArrangement = Arrangement.spacedBy(Spacing.space8), verticalAlignment = Alignment.Top,
    ) {
        Icon(Lucide.CircleX, Modifier.size(16.dp), tint = c)
        Text(text, Modifier.weight(1f), style = TextStyles.caption, lineHeight = 18.sp, color = c)
    }
}

/** Indeterminate circular spinner. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = color(mutedColor)) {
    val angle by rememberInfiniteTransition().animateFloat(
        0f, 360f, infiniteRepeatable(tween(Motion.spin, easing = Motion.linear)),
    )
    Icon(Lucide.LoaderCircle, modifier.size(size).rotate(angle), tint = tint)
}

/** Selectable row for lists such as the Session sidebar; highlights on hover and selection. */
@Composable
fun ListRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.hovered()
    val shape = Theme[shapes][mediumShape]
    val bg = when {
        selected -> color(selectedControlColor)
        hovered && enabled -> color(selectedControlColor).copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg, shape)
            .hoverable(interaction)
            .clickable(interaction, Theme[indications][defaultIndication], enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = if (LocalInteractionMode.current == InteractionMode.Touch) 48.dp else 0.dp)
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.space10, vertical = Spacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.space10),
    ) {
        leading?.let { ProvideContentColor(color(mutedColor)) { it() } }
        content()
    }
}

/** Uppercase muted caption for grouping, e.g. "Sessions". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(horizontal = Spacing.space10, vertical = Spacing.space6),
        style = TextStyles.overline,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.Normal,
        color = color(mutedColor),
    )
}

/** Hover state; touch screens have none, though some platforms report a tap as one that never ends. */
@Composable
fun MutableInteractionSource.hovered(): Boolean {
    val hovered by collectIsHoveredAsState()
    return hovered && LocalInteractionMode.current != InteractionMode.Touch
}

/** A [label] tooltip for pointer users; touch screens read the label from the control instead. */
@Composable
fun Hint(label: String, side: TooltipSide = TooltipSide.Top, content: @Composable () -> Unit) {
    if (LocalInteractionMode.current == InteractionMode.Touch) content()
    else Tooltip(side = side, panel = { TooltipPanel { Text(label, style = TextStyles.caption) } }) { content() }
}

/** An action inside a field, e.g. Generate or Open: a quiet icon that leaves the field's own shape alone. */
@Composable
fun FieldButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
        Icon(icon, Modifier.size(18.dp), tint = color(mutedColor), contentDescription = label)
    }
}

/** Fully rounded ends, for pills and chips. */
internal val PillShape = RoundedCornerShape(50)

/** Text typed into inputs: a notch under body text. */
@Composable
fun inputTextStyle(): TextStyle {
    val touch = LocalInteractionMode.current == InteractionMode.Touch
    // Inter, like the messages and values it becomes.
    return LocalTextStyle.current.copy(fontFamily = interFamily(), fontSize = if (touch) 16.sp else 13.sp, lineHeight = if (touch) 22.sp else 19.sp)
}

/** The keyboard starts sentences with a capital when [capitalize]; off for URLs, IDs, keys and commands. */
fun inputKeyboard(capitalize: Boolean) = KeyboardOptions(capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None)

/** Bordered text input that stays visible on any surface; [capitalize] for prose such as names and notes. */
@Composable
fun Input(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    capitalize: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    error: Boolean = false,
    compact: Boolean = false,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    keyboardOptions: KeyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else inputKeyboard(capitalize),
    enabled: Boolean = true,
) {
    val style = if (compact) TextStyles.caption else inputTextStyle()
    var focused by remember { mutableStateOf(false) }
    val shape = Theme[shapes][mediumShape]
    val minHeight = if (compact) 36.dp else if (LocalInteractionMode.current == InteractionMode.Touch) 48.dp else 40.dp
    val verticalPadding = if (lineLimits is TextFieldLineLimits.MultiLine) with(LocalDensity.current) {
        ((minHeight - style.lineHeight.toDp()) / 2).coerceAtLeast(0.dp)
    } else 0.dp
    BasicTextField(
        state,
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        textStyle = style.copy(color = color(onFieldColor)),
        cursorBrush = SolidColor(color(accentColor)),
        lineLimits = lineLimits,
        outputTransformation = if (secret) OutputTransformation { replace(0, length, "•".repeat(length)) } else null,
        keyboardOptions = keyboardOptions,
        decorator = { inner ->
            Row(
                Modifier.clip(shape).background(color(backgroundColor), shape)
                    .border(1.dp, when {
                        error -> color(destructiveColor)
                        focused -> color(accentColor).copy(alpha = 0.55f)
                        else -> color(borderColor)
                    }, shape)
                    .heightIn(min = minHeight)
                    .padding(horizontal = if (compact) 6.dp else 12.dp,
                        vertical = verticalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f)) {
                    if (state.text.isEmpty()) Text(placeholder, style = style, color = color(mutedColor))
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}
