package kinuvo.ui.design

import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.surface.dateTimeValue
import kinuvo.app.surface.sliderValue

@Composable
fun Slider(value: Double, onChange: (Double) -> Unit, min: Double, max: Double, divisions: Int? = null,
    label: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier) {
    if (!min.toFloat().isFinite() || !max.toFloat().isFinite()) return
    val current = sliderValue(value, min, max, divisions) ?: return
    val latest by rememberUpdatedState(onChange)
    val track = color(subtleColor)
    val active = color(accentColor)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.orEmpty(), style = TextStyles.secondary)
            Text(if (current % 1.0 == 0.0) current.toLong().toString() else current.toString(), style = TextStyles.secondary)
        }
        Canvas(Modifier.fillMaxWidth().height(44.dp).semantics {
            contentDescription = label ?: "Value"
            progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), min.toFloat()..max.toFloat(), (divisions ?: 1).coerceIn(1, 1000) - 1)
            if (!enabled) disabled()
            setProgress { next -> if (enabled) sliderValue(next.toDouble(), min, max, divisions)?.let(latest); enabled }
        }.onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) false
            else {
                val direction = when (event.key) {
                    Key.DirectionRight, Key.DirectionUp -> 1
                    Key.DirectionLeft, Key.DirectionDown -> -1
                    else -> 0
                }
                if (direction != 0) sliderValue(current + direction * (max - min) / (divisions?.coerceIn(1, 1000) ?: 100), min, max, divisions)?.let(latest)
                direction != 0
            }
        }.focusable(enabled).pointerInput(min, max, divisions, enabled) {
            if (enabled) detectTapGestures { point -> sliderValue(min + point.x / size.width * (max - min), min, max, divisions)?.let(latest) }
        }.pointerInput(min, max, divisions, enabled) {
            if (enabled) detectDragGestures { change, _ ->
                change.consume()
                sliderValue(min + change.position.x / size.width * (max - min), min, max, divisions)?.let(latest)
            }
        }) {
            val y = size.height / 2
            val x = ((current - min) / (max - min) * size.width).toFloat()
            drawLine(track, Offset(0f, y), Offset(size.width, y), 4.dp.toPx())
            drawLine(active, Offset(0f, y), Offset(x, y), 4.dp.toPx())
            drawCircle(active, 8.dp.toPx(), Offset(x.coerceIn(8.dp.toPx(), (size.width - 8.dp.toPx()).coerceAtLeast(8.dp.toPx())), y))
        }
    }
}

@Composable
fun DateTimeInput(value: String, onChange: (String) -> Unit, enableDate: Boolean, enableTime: Boolean,
    min: String? = null, max: String? = null, label: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier,
    onValidityChange: (Boolean) -> Unit = {}) {
    val state = rememberTextFieldState(value)
    LaunchedEffect(value) { if (state.text.toString() != value) state.edit { replace(0, length, value) } }
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(state, enableDate, enableTime, min, max, enabled) {
        snapshotFlow { state.text.toString() }.collect { if (enabled) dateTimeValue(it, enableDate, enableTime, min, max)?.let(latest) }
    }
    val invalid = dateTimeValue(state.text.toString(), enableDate, enableTime, min, max) == null
    SideEffect { onValidityChange(!invalid) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        label?.let { Text(it, style = TextStyles.secondary) }
        if (enabled && (enableDate || enableTime)) Input(state, when {
            enableDate && enableTime -> "YYYY-MM-DDTHH:MM:SS"
            enableDate -> "YYYY-MM-DD"
            enableTime -> "HH:MM:SS"
            else -> "Date and time disabled"
        }, error = invalid)
        else Text(value, style = TextStyles.secondary)
        if (invalid) Text("Enter a valid ISO 8601 value within the allowed range", style = TextStyles.caption, color = color(destructiveColor))
    }
}

@Composable
fun MediaCard(title: String, description: String?, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space12)) {
            IconButton(onClick = onPlay, style = ButtonStyle.Outlined) { Icon(Lucide.Play, Modifier.size(20.dp), contentDescription = "Open $title") }
            Column {
                Text(title, style = TextStyles.label)
                description?.let { Text(it, style = TextStyles.secondary, color = color(mutedColor)) }
                Text("Opens with your default app", style = TextStyles.caption, color = color(mutedColor))
            }
        }
    }
}

@Composable
fun Tabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
        Segmented(titles, selected, onSelect = onSelect)
        content()
    }
}

@Composable
fun ChoiceSelect(options: List<Pair<String, String>>, selected: String?, enabled: Boolean, compact: Boolean = false, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val style = TextStyles.secondary
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = with(density) { (options.maxOfOrNull { measurer.measure(it.second, style, softWrap = false).size.width } ?: 0).toDp() } + 48.dp
    DropdownMenu(expanded = open, onExpandedChange = { open = it }, panel = {
        DropdownMenuPanel {
            options.forEach { (value, label) -> DropdownMenuItem(onClick = { open = false; onSelect(value) }) { Text(label, style = style) } }
        }
    }) {
        val shape = RoundedCornerShape(10.dp)
        Row((if (compact) Modifier.fillMaxWidth().height(36.dp) else Modifier.widthIn(min = width).height(40.dp))
            .clip(shape).background(color(backgroundColor)).border(1.dp, color(borderColor), shape)
            .clickable(enabled = enabled) { open = true }.padding(horizontal = if (compact) 6.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(options.firstOrNull { it.first == selected }?.second ?: "Select", Modifier.weight(1f), style = if (compact) TextStyles.caption else style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Lucide.ChevronDown, Modifier.size(12.dp), contentDescription = null)
        }
    }
}

@Composable
fun SurfaceBadge(label: String, tone: Tone, variant: String?, size: String?, pill: Boolean, modifier: Modifier = Modifier) {
    val tint = tone.color()
    val shape = RoundedCornerShape(if (pill) 100.dp else 4.dp)
    val padding = when (size) { "lg" -> 10.dp; "sm" -> 4.dp; else -> 6.dp }
    Box(modifier.background(if (variant == "solid") tint else tint.copy(alpha = if (variant == "outline") 0f else 0.12f), shape)
        .then(if (variant == "outline") Modifier.border(1.dp, tint, shape) else Modifier).padding(horizontal = padding, vertical = 3.dp)) {
        Text(label, style = if (size == "lg") TextStyles.secondary else TextStyles.caption,
            color = if (variant == "solid") color(backgroundColor) else tint)
    }
}
