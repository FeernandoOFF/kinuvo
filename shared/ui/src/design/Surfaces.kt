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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.composeunstyled.LocalTextStyle
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.LoaderCircle
import com.composables.icons.lucide.Lucide
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import com.composeunstyled.ProvideContentColor

/** A bordered container for cards, menus and code. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = color(panelColor),
    borderColor: Color = color(com.composables.ui.theme.borderColor),
    shape: Shape = Theme[shapes][mediumShape],
    contentPadding: PaddingValues = PaddingValues(12.dp),
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
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(c.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (loading) Spinner(size = 12.dp, tint = c) else icon?.let { Icon(it, Modifier.size(12.dp), tint = c) }
        Text(text, Modifier.weight(1f, fill = false), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, color = c)
    }
}

/** A compact tag beside a title, e.g. `Task`. */
@Composable
fun Badge(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val c = color(mutedColor)
    Row(
        modifier.clip(RoundedCornerShape100).border(1.dp, color(borderColor), RoundedCornerShape100).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        icon?.let { Icon(it, Modifier.size(10.dp), tint = c) }
        Text(text, fontSize = 10.sp, lineHeight = 14.sp, color = c, singleLine = true)
    }
}

/** An error message that remains readable at phone widths. */
@Composable
fun ErrorMessage(text: String, modifier: Modifier = Modifier) {
    val c = Tone.Danger.color()
    Row(
        modifier.fillMaxWidth().clip(Theme[shapes][mediumShape]).background(c.copy(alpha = 0.12f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top,
    ) {
        Icon(Lucide.CircleX, Modifier.size(16.dp), tint = c)
        Text(text, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp, color = c)
    }
}

/** Indeterminate circular spinner. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = color(mutedColor)) {
    val angle by rememberInfiniteTransition().animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)),
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
    val hovered by interaction.collectIsHoveredAsState()
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
        modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.Normal,
        color = color(mutedColor),
    )
}

internal val RoundedCornerShape100 = androidx.compose.foundation.shape.RoundedCornerShape(100)

/** Text typed into inputs: a notch under body text. */
@Composable
fun inputTextStyle(): TextStyle {
    val touch = LocalInteractionMode.current == InteractionMode.Touch
    return LocalTextStyle.current.copy(fontSize = if (touch) 15.sp else 13.sp, lineHeight = if (touch) 22.sp else 19.sp)
}

/** The keyboard starts sentences with a capital when [capitalize]; off for URLs, IDs, keys and commands. */
fun inputKeyboard(capitalize: Boolean) = KeyboardOptions(capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None)

/** Single-line bordered text input that stays visible on any surface; [capitalize] for prose such as names and notes. */
@Composable
fun Input(
    state: androidx.compose.foundation.text.input.TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    capitalize: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val style = inputTextStyle()
    com.composables.ui.components.TextField(
        state,
        modifier.fillMaxWidth(),
        trailing = trailing,
        backgroundColor = color(backgroundColor),
        borderColor = color(com.composables.ui.theme.borderColor),
        placeholder = { Text(placeholder, style = style) },
        textStyle = style,
        outputTransformation = if (secret) androidx.compose.foundation.text.input.OutputTransformation { replace(0, length, "•".repeat(length)) } else null,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else inputKeyboard(capitalize),
    )
}
