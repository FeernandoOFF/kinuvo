package kinuvo.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.compose.ripple.rememberRippleIndication
import com.composables.interactioncapabilities.currentInteractionCapabilities
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.ThemeToken
import com.composeunstyled.theme.buildTheme
import org.jetbrains.compose.resources.Font
import ui.generated.resources.Res
import ui.generated.resources.libron_bold
import ui.generated.resources.libron_bolditalic
import ui.generated.resources.libron_italic
import ui.generated.resources.libron_regular

/** Kinuvo-only color tokens, on top of the Composables UI ones. */
val accentColor = ThemeToken<Color>("accent")
val onAccentColor = ThemeToken<Color>("on_accent")
val sidebarColor = ThemeToken<Color>("sidebar")
val railColor = ThemeToken<Color>("rail")
val subtleColor = ThemeToken<Color>("subtle")
val codeColor = ThemeToken<Color>("code")
val onCodeColor = ThemeToken<Color>("on_code")
val warningColor = ThemeToken<Color>("warning")
val successColor = ThemeToken<Color>("success")

private class Palette(
    val background: Color, val sidebar: Color, val rail: Color, val panel: Color, val field: Color, val subtle: Color,
    val text: Color, val muted: Color, val border: Color, val accent: Color, val onAccent: Color,
    val code: Color, val onCode: Color, val destructive: Color, val warning: Color, val success: Color,
)

private val Light = Palette(
    background = Color(0xFFFAF9F5), sidebar = Color(0xFFF3F1EA), rail = Color(0xFFE3DFD4), panel = Color.White, field = Color.White,
    subtle = Color(0xFFF0EEE6), text = Color(0xFF1F1E1B), muted = Color(0xFF75716A), border = Color(0xFFE3E0D6),
    accent = Color(0xFFC96442), onAccent = Color.White, code = Color(0xFFF3F1EA), onCode = Color(0xFF2B2A27),
    destructive = Color(0xFFC0392B), warning = Color(0xFFB7791F), success = Color(0xFF2F855A),
)

private val Dark = Palette(
    background = Color(0xFF262624), sidebar = Color(0xFF1F1E1D), rail = Color(0xFF131211), panel = Color(0xFF30302E), field = Color(0xFF30302E),
    subtle = Color(0xFF353533), text = Color(0xFFEDEDE6), muted = Color(0xFF9C9A92), border = Color(0xFF3F3E3B),
    accent = Color(0xFFD97757), onAccent = Color.White, code = Color(0xFF1C1B1A), onCode = Color(0xFFE6E4DC),
    destructive = Color(0xFFF07167), warning = Color(0xFFF6C177), success = Color(0xFF7FD1A0),
)

private fun Palette.withAccent(accent: Color) = Palette(
    background, sidebar, rail, panel, field, subtle, text, muted, border,
    accent, if (accent.luminance() > 0.4f) Color(0xFF1F1E1B) else Color.White, code, onCode, destructive, warning, success,
)

private val LocalAccent = compositionLocalOf<Color?> { null }

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

private val HarnessThemeComposable = buildTheme {
    name = "Kinuvo"
    val dark = LocalColorScheme.current == ColorScheme.Dark
    val base = if (dark) Dark else Light
    val p = LocalAccent.current?.let { base.withAccent(it) } ?: base
    val touch = !currentInteractionCapabilities().hasPointer

    properties[colors] = mapOf(
        backgroundColor to p.background,
        onBackgroundColor to p.text,
        panelColor to p.panel,
        onPanelColor to p.text,
        mutedColor to p.muted,
        primaryColor to p.accent,
        onPrimaryColor to p.onAccent,
        secondaryColor to p.subtle,
        onSecondaryColor to p.text,
        controlColor to p.subtle,
        onControlColor to p.muted,
        thumbColor to Color.White,
        switchTrackColor to p.border,
        switchSelectedTrackColor to p.accent,
        switchThumbColor to Color.White,
        selectedControlColor to (if (dark) Color(0xFF3A3A37) else Color(0xFFE8E5DC)),
        onSelectedControlColor to p.text,
        destructiveColor to p.destructive,
        onDestructiveColor to Color.White,
        borderColor to p.border,
        fieldColor to p.field,
        onFieldColor to p.text,
        scrimColor to Color.Black.copy(alpha = if (dark) 0.5f else 0.32f),
        ringColor to p.accent.copy(alpha = 0.5f),
        accentColor to p.accent,
        onAccentColor to p.onAccent,
        sidebarColor to p.sidebar,
        railColor to p.rail,
        subtleColor to p.subtle,
        codeColor to p.code,
        onCodeColor to p.onCode,
        warningColor to p.warning,
        successColor to p.success,
    )
    val selection = TextSelectionColors(handleColor = p.accent, backgroundColor = p.accent.copy(alpha = 0.28f))
    defaultTextSelectionColors = selection
    properties[textSelectionColors] = mapOf(com.composables.ui.theme.defaultTextSelectionColors to selection)
    properties[shapes] = mapOf(
        smallShape to RoundedCornerShape(6.dp),
        mediumShape to RoundedCornerShape(10.dp),
        largeShape to RoundedCornerShape(16.dp),
        buttonShape to RoundedCornerShape(if (touch) 100.dp else 8.dp),
        dialogShape to RoundedCornerShape(16.dp),
        sheetShape to RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        menuShape to RoundedCornerShape(12.dp),
        fieldShape to RoundedCornerShape(if (touch) 14.dp else 10.dp),
    )
    properties[shadows] = mapOf(
        raisedShadow to Shadow(radius = 12.dp, color = Color.Black, offset = DpOffset(0.dp, 2.dp), alpha = if (dark) 0.3f else 0.08f),
        overlayShadow to Shadow(radius = 24.dp, color = Color.Black, offset = DpOffset(0.dp, 8.dp), alpha = if (dark) 0.4f else 0.16f),
    )
    properties[alphas] = mapOf(disabledAlpha to 0.4f)

    val fontFamily = FontFamily(
        Font(Res.font.libron_regular, FontWeight.Normal),
        Font(Res.font.libron_bold, FontWeight.Bold),
        Font(Res.font.libron_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.libron_bolditalic, FontWeight.Bold, FontStyle.Italic),
    )
    defaultTextStyle = TextStyle(fontFamily = fontFamily, fontSize = if (touch) 17.sp else 14.sp, lineHeight = if (touch) 26.sp else 21.sp, fontWeight = FontWeight.Normal)
    defaultContentColor = p.text
    val ripple = rememberRippleIndication(p.text.copy(alpha = 0.08f))
    defaultIndication = ripple
    properties[indications] = mapOf(
        com.composables.ui.theme.defaultIndication to ripple,
        inverseIndication to rememberRippleIndication(p.background.copy(alpha = 0.12f)),
    )

    extend { content ->
        CompositionLocalProvider(LocalInteractionMode provides if (touch) InteractionMode.Touch else InteractionMode.Pointer) {
            content()
        }
    }
}

/** Kinuvo design system root: Composables UI tokens with the Kinuvo palette, following the system dark mode. [accent] overrides the palette's. */
@Composable
fun HarnessTheme(dark: Boolean = isSystemInDarkTheme(), accent: Color? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColorScheme provides if (dark) ColorScheme.Dark else ColorScheme.Light, LocalAccent provides accent) {
        HarnessThemeComposable(content)
    }
}

/** Shorthand for reading a color token: `color(accentColor)`. */
@Composable
fun color(token: ThemeToken<Color>): Color = Theme[colors][token]
