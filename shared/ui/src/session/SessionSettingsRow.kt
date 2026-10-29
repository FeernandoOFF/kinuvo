package kinuvo.ui.session

import kinuvo.app.session.*

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*

import androidx.compose.foundation.Canvas
import kotlinx.coroutines.launch
import kinuvo.ui.design.onAccentColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import com.composables.ui.theme.smallShape
import androidx.compose.ui.draw.clip
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onFieldColor
import kinuvo.protocol.*
import kinuvo.ui.design.accentColor
import kinuvo.ui.design.color
import kinuvo.ui.design.ListRow
import kinuvo.ui.design.SectionLabel
import kinuvo.ui.design.LocalPopovers
import kinuvo.ui.design.Popover
import com.composables.ui.theme.InteractionMode
import com.composables.ui.theme.LocalInteractionMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Composer's left-to-right controls: attach, and this Session's own settings: when to ask before acting, and model and
 * reasoning effort. Unset, they follow the [agent]'s defaults, which [onAgentDefaults] opens. [photos] and [files] say
 * whether each attachment kind is accepted; null when unknown.
 */
@Composable
internal fun RowScope.ComposerSettings(
    settings: SessionSettings, defaults: AgentSettings, models: ModelsCatalog, onChange: (SessionSettings) -> Unit, onAttach: (AttachmentSource) -> Unit = {},
    canAttach: Boolean = true, onRefreshModels: () -> Unit = {}, photos: CapabilityState? = null, files: CapabilityState? = null,
    agent: String? = null, onAgentDefaults: (() -> Unit)? = null,
) {
    var attachOpen by remember { mutableStateOf(false) }
    var approvalsOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    val custom = rememberTextFieldState()
    var customProvider by remember(settings.provider, defaults.provider) { mutableStateOf(settings.provider ?: defaults.provider) }
    val provider = settings.provider ?: defaults.provider
    val selected = models.model(provider, settings.model ?: defaults.model)
    val levels = selected?.reasoning ?: REASONING
    val effort = settings.reasoning ?: selected?.reasoningFor(defaults.reasoning) ?: defaults.reasoning
    val approvals = settings.approvals ?: defaults.approvals

    // Touch screens get sheets over the app, the rest blurred; pointers get menus by the button.
    val sheets = LocalInteractionMode.current == InteractionMode.Touch && LocalPopovers.current != null
    val sources = listOfNotNull(
        Triple(AttachmentSource.Camera, "Camera", Lucide.Camera).takeIf { hasCamera },
        Triple(AttachmentSource.Photos, "Photos", Lucide.Image),
        Triple(AttachmentSource.Files, "Files", Lucide.FileUp),
    )
    fun accepts(source: AttachmentSource) = canAttach && (if (source == AttachmentSource.Files) files else photos)?.actionable != false
    val attach = { source: AttachmentSource -> attachOpen = false; onAttach(source) }
    val unavailable = listOfNotNull(photos, files).takeIf { it.size == 2 && it.none(CapabilityState::actionable) }
    val attachButton: @Composable () -> Unit = {
        ControlButton(Lucide.Plus, unavailable?.firstNotNullOfOrNull { it.reason }?.let { "Attach: $it" } ?: "Attach", enabled = unavailable == null) { attachOpen = true }
    }
    val approvalOptions = listOf<String?>(null) + APPROVALS.keys
    fun approvalTitle(option: String?) = if (option == null) "Agent's setting (${defaults.approvals})" else option.replaceFirstChar { it.uppercase() }
    val pickApproval = { option: String? -> approvalsOpen = false; onChange(settings.copy(approvals = option)) }
    val approvalsButton: @Composable () -> Unit = { ControlButton(Lucide.Hand, "Ask before acting in this Session: $approvals") { approvalsOpen = true } }
    // Says whose settings these are, and leads to the Agent's defaults that every other Session follows.
    val scope: @Composable (close: () -> Unit) -> Unit = { close ->
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("This Session only", fontSize = 12.sp, color = color(mutedColor))
            onAgentDefaults?.let { open ->
                Row(Modifier.clip(Theme[shapes][smallShape]).clickable { close(); open() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Lucide.Settings2, Modifier.size(14.dp), tint = color(accentColor))
                    Text("Edit ${agent ?: "the Agent"}'s defaults", fontSize = 13.sp, color = color(accentColor))
                }
            }
        }
    }

    if (sheets) {
        attachButton()
        Popover(attachOpen, { attachOpen = false }, "Add to chat") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { (source, label, icon) -> SourceTile(label, icon, accepts(source), Modifier.weight(1f)) { attach(source) } }
            }
            Text(ATTACHMENT_LIMITS, Modifier.fillMaxWidth().padding(top = 12.dp), fontSize = 12.sp, color = color(mutedColor), textAlign = TextAlign.Center)
        }
        approvalsButton()
        Popover(approvalsOpen, { approvalsOpen = false }, "Ask before acting") {
            scope { approvalsOpen = false }
            approvalOptions.forEach { option ->
                ListRow(settings.approvals == option, { pickApproval(option) }) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(approvalTitle(option))
                        Text(APPROVALS[option ?: defaults.approvals].orEmpty(), fontSize = 13.sp, color = color(mutedColor))
                    }
                    if (settings.approvals == option) Icon(Lucide.Check, Modifier.size(16.dp))
                }
            }
        }
    } else {
        DropdownMenu(
            expanded = attachOpen,
            onExpandedChange = { attachOpen = it },
            panel = {
                DropdownMenuPanel {
                    sources.forEach { (source, label) -> DropdownMenuItem(onClick = { attach(source) }, enabled = accepts(source)) { Text(label) } }
                    DropdownMenuLabel { Text(ATTACHMENT_LIMITS, fontSize = 12.sp) }
                }
            },
        ) { attachButton() }

        DropdownMenu(
            expanded = approvalsOpen,
            onExpandedChange = { approvalsOpen = it },
            panel = {
                DropdownMenuPanel {
                    DropdownMenuLabel { Text("Ask before acting", fontSize = 12.sp) }
                    scope { approvalsOpen = false }
                    approvalOptions.forEach { option ->
                        DropdownMenuItem(
                            onClick = { pickApproval(option) },
                            trailing = if (settings.approvals == option) ({ Icon(Lucide.Check, Modifier.size(14.dp)) }) else null,
                        ) {
                            Column {
                                Text(approvalTitle(option))
                                Text(APPROVALS[option ?: defaults.approvals].orEmpty(), fontSize = 12.sp, color = color(mutedColor))
                            }
                        }
                    }
                }
            },
        ) { approvalsButton() }
    }

    Spacer(Modifier.weight(1f))

    val closeModel = { modelOpen = false; listing = false }
    // Rows rather than menu items, so the same picker fits the desktop menu and the phone popover.
    // The menu measures its content's intrinsic width, which the effort track can't give, so it gets a fixed one.
    val modelPicker: @Composable (width: Modifier) -> Unit = { width ->
        if (!listing) Column(width.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth()) { scope(closeModel) }
            Row(Modifier.clickable { listing = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected?.name ?: settings.model ?: defaults.model, Modifier.weight(1f, fill = false), singleLine = true, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Text(effort.replaceFirstChar { it.uppercase() }, color = color(mutedColor), singleLine = true)
                Icon(Lucide.ChevronRight, Modifier.size(16.dp), tint = color(mutedColor))
            }
            if (levels.isEmpty()) Text("Reasoning not supported", fontSize = 12.sp, color = color(mutedColor))
            else EffortTrack(levels, levels.indexOf(effort)) { onChange(settings.copy(reasoning = levels[it])) }
        } else Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            ListRow(false, { listing = false; onChange(settings.copy(model = null, provider = null, reasoning = null)) }) {
                Text("Agent's model (${defaults.model})", Modifier.weight(1f))
            }
            models.providers.forEach { group ->
                SectionLabel(group.name)
                group.models.forEach { model ->
                    ListRow(false, { listing = false; onChange(settings.select(group.id, model, defaults)) }) {
                        Text(model.name, Modifier.weight(1f))
                        if (provider == group.id && settings.model == model.id) Icon(Lucide.Check, Modifier.size(14.dp))
                    }
                }
                group.warning?.let { Text(it, Modifier.padding(8.dp), fontSize = 12.sp, color = color(mutedColor)) }
            }
            if (settings.model != null && selected == null) SectionLabel("Current: ${settings.model}")
            HorizontalSeparator(Modifier.padding(vertical = 6.dp))
            ListRow(false, { onRefreshModels() }) { Text("Refresh models", Modifier.weight(1f)) }
            ListRow(false, { customOpen = !customOpen }) { Text("Custom model…", Modifier.weight(1f)) }
            if (customOpen) Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker((models.providers.map { it.id } + provider).distinct(), customProvider,
                    { id -> models.providers.firstOrNull { it.id == id }?.name ?: id }, { customProvider = it })
                TextField(custom, placeholder = { Text("Model ID") })
                Button(onClick = {
                    listing = false
                    val id = custom.text.toString().trim()
                    val model = models.model(customProvider, id)
                    onChange(if (model != null) settings.select(customProvider, model, defaults)
                        else settings.copy(provider = customProvider, model = id, reasoning = null))
                }, enabled = custom.text.isNotBlank(), buttonSize = ButtonSize.Small) { Text("Use model") }
            }
            models.warning?.let { Text(it, Modifier.padding(8.dp), fontSize = 12.sp, color = color(mutedColor)) }
        }
    }
    val gauge: @Composable () -> Unit = {
        IconButton(
            onClick = { modelOpen = true }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Model and reasoning: ${selected?.name ?: defaults.model}, $effort" },
            style = ButtonStyle.Ghost, shape = CircleShape,
        ) { EffortGauge(if (levels.size > 1) levels.indexOf(effort).coerceAtLeast(0) / (levels.size - 1f) else 1f) }
    }
    if (sheets) {
        gauge()
        Popover(modelOpen, closeModel) { modelPicker(Modifier.fillMaxWidth()) }
    } else DropdownMenu(
        expanded = modelOpen,
        onExpandedChange = { if (it) modelOpen = true else closeModel() },
        panel = { DropdownMenuPanel { modelPicker(Modifier.width(280.dp)) } },
    ) { gauge() }
}

@Composable
private fun SourceTile(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = Theme[shapes][largeShape]
    Column(
        modifier.height(88.dp).clip(shape).background(color(borderColor).copy(alpha = 0.5f), shape).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, Modifier.size(22.dp))
        Text(label, fontSize = 14.sp)
    }
}

private const val ATTACHMENT_LIMITS = "4 files · 10 MB each"

@Composable
private fun ControlButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp), style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(onFieldColor)) {
        Icon(icon, Modifier.size(22.dp), contentDescription = label)
    }
}

/** Speedometer whose accent arc fills with [fraction] of the available reasoning effort. */
@Composable
private fun EffortGauge(fraction: Float) {
    val track = color(mutedColor)
    val accent = color(accentColor)
    val needle = color(onFieldColor)
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val arc = Stroke(stroke, cap = StrokeCap.Round)
        val topLeft = Offset(inset, inset)
        val box = size.copy(size.width - stroke, size.height - stroke)
        drawArc(track.copy(alpha = 0.4f), 135f, 270f, false, topLeft, box, style = arc)
        drawArc(accent, 135f, 270f * fraction, false, topLeft, box, style = arc)
        val angle = (135f + 270f * fraction) * PI / 180
        val r = size.minDimension * 0.28f
        drawLine(needle, center, center + Offset((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat()), stroke, StrokeCap.Round)
        drawCircle(needle, stroke, center)
    }
}

/** Effort slider: drag or tap the thumb to a level; the accent trail follows it and settles on the nearest level. */
@Composable
private fun EffortTrack(levels: List<String>, current: Int, onPick: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val accent = color(accentColor)
    val onAccent = color(onAccentColor)
    val muted = color(mutedColor)
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp).background(color(borderColor).copy(alpha = 0.5f), CircleShape).padding(8.dp)) {
        val slot = constraints.maxWidth.toFloat() / levels.size
        fun centerOf(i: Int) = slot * (i.coerceIn(levels.indices) + 0.5f)
        fun indexAt(x: Float) = (x / slot).toInt().coerceIn(levels.indices)
        val thumb = remember(slot) { Animatable(centerOf(current)) }
        var dragged by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(current, slot) { if (dragged == null) thumb.animateTo(centerOf(current)) }
        fun settle(x: Float) {
            val i = indexAt(x)
            scope.launch {
                thumb.snapTo(x)
                dragged = null
                thumb.animateTo(centerOf(i))
            }
            if (i != current) onPick(i)
        }
        val x = dragged ?: thumb.value
        Box(
            Modifier.fillMaxSize()
                .pointerInput(slot, current) { detectTapGestures { settle(it.x) } }
                .pointerInput(slot, current) {
                    detectHorizontalDragGestures(onDragEnd = { settle(dragged ?: thumb.value) }, onDragCancel = { settle(dragged ?: thumb.value) }) { change, delta ->
                        change.consume()
                        dragged = ((dragged ?: thumb.value) + delta).coerceIn(centerOf(0), centerOf(levels.lastIndex))
                    }
                },
        ) {
            with(density) {
                Box(Modifier.fillMaxHeight().width(x.toDp() + 20.dp).background(accent, CircleShape))
                levels.forEachIndexed { i, level ->
                    Box(
                        Modifier.offset(centerOf(i).toDp() - 5.dp).align(Alignment.CenterStart).size(10.dp)
                            .background(if (centerOf(i) < x) onAccent.copy(alpha = 0.45f) else muted, CircleShape)
                            .semantics { contentDescription = level },
                    )
                }
                Box(Modifier.offset(x.toDp() - 20.dp).size(40.dp).background(onAccent, CircleShape))
            }
        }
    }
}
