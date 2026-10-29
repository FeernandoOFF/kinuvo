package kinuvo.ui.computer

import kinuvo.ui.navigation.*
import kinuvo.app.computer.*
import kinuvo.ui.agent.*
import kinuvo.ui.session.*

import androidx.compose.foundation.Image
import com.composeunstyled.ProvideContentColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.protocol.ComputerControl
import kinuvo.protocol.ComputerInfo
import kinuvo.protocol.ComputerFrame
import kinuvo.protocol.Platform
import kinuvo.ui.design.*
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Computers Agents can control; one opens to its Live view, beside the list on wide screens. */
@Composable
fun ComputersScreen(computers: ComputersViewModel, onOpen: (String) -> Unit, onBack: (() -> Unit)?) {
    val state by computers.state.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val open = state.computer
        if (maxWidth >= 900.dp) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(380.dp).fillMaxHeight().background(color(sidebarColor))) { ComputerList(state, computers::retry, onOpen) }
            VerticalSeparator()
            Box(Modifier.weight(1f)) {
                if (open != null) LiveView(state, open, computers, back = null)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pick a Computer to watch it.", color = color(mutedColor)) }
            }
        } else if (open != null) LiveView(state, open, computers, onBack)
        else ComputerList(state, computers::retry, onOpen)
    }
}

@Composable
internal fun ComputerList(state: ComputersState, onRetry: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(Lucide.MonitorSmartphone, "Computers")
        val all = state.computers
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                all == null && state.error != null -> {
                    ErrorMessage(state.error!!)
                    RowGroup { ActionRow("Retry", Lucide.RefreshCw, onRetry) }
                }
                all == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
                all.isEmpty() -> Text("No Computers yet. Allow computer use on a phone or computer under Settings → This Device's tools, and turn on computer use in an Agent's tools.",
                    Modifier.padding(horizontal = 6.dp), color = color(mutedColor))
                else -> RowGroup {
                    all.forEach { c ->
                        GroupRow(c.name, { onOpen(c.id) }, "${c.platform} · ${c.status()}", c.icon(), selected = c.id == state.selected) {
                            when {
                                c.takeoverRequest != null -> Pill("Needs you", tone = Tone.Warning)
                                c.control == ComputerControl.Agent -> Pill("Working", tone = Tone.Accent, loading = true)
                                c.control == ComputerControl.User -> Pill("You", tone = Tone.Warning)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What [computer] shows, who controls it, and while the user does, their taps, swipes, keys and typing. */
@Composable
private fun LiveView(state: ComputersState, computer: ComputerInfo, computers: ComputersViewModel, back: (() -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(computer.name, back, subtitle = computer.platform)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RowGroup {
                GroupRow(computer.status(), icon = if (state.control == ComputerControl.User) Lucide.Hand else computer.icon(), chevron = false,
                    subtitle = if (state.control == ComputerControl.User) "Tap, swipe and type on the screen below" else null) {
                    if (state.control == ComputerControl.User) Button(onClick = computers::handBack, enabled = !state.busy, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text("Hand back") }
                    else Button(onClick = computers::takeOver, enabled = !state.busy && computer.available, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) { Text("Take over") }
                }
                computer.takeoverRequest?.let { reason -> GroupRow("The Agent asks you to take over", subtitle = reason, icon = Lucide.CircleAlert, tint = Tone.Warning.color(), chevron = false) }
            }
            state.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
            ComputerDisplay(state.frame, state.control == ComputerControl.User, computers::tap, computers::swipe, Modifier.weight(1f).fillMaxWidth())
            if (state.control == ComputerControl.User) ComputerControls(Platform.fromName(computer.platform), computers::key, computers::type)
        }
    }
}

private fun ComputerInfo.status() = when {
    !available -> "Offline"
    control == ComputerControl.User -> "You're in control"
    control == ComputerControl.Agent -> "Agent is working"
    else -> "Idle"
}

private fun ComputerInfo.icon() = if (platform.lowercase() in setOf("android", "ios", "ipados")) Lucide.Smartphone else Lucide.Monitor

/**
 * The latest frame, scaled to fit; two fingers zoom and pan it. While [interactive], taps and drags on it go to the
 * Computer in its own pixels, wherever it's zoomed.
 */
@Composable
internal fun ComputerDisplay(frame: ComputerFrame?, interactive: Boolean, onTap: (Int, Int) -> Unit, onSwipe: (Int, Int, Int, Int) -> Unit, modifier: Modifier) {
    val screen = frame?.screen
    val computerId = frame?.computer?.id
    // Decoded off the main thread at full size; the last frame stays up until the next is ready.
    var image by remember(computerId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(computerId, screen?.screenshot) {
        val shot = screen?.screenshot ?: return@LaunchedEffect
        withContext(Dispatchers.Default) { runCatching { attachmentImage(Base64.decode(shot), fullSize = true) }.getOrNull() }?.let { image = it }
    }
    var zoom by remember(computerId) { mutableStateOf(1f) }
    var pan by remember(computerId) { mutableStateOf(Offset.Zero) }
    Box(modifier.clipToBounds().pointerInput(computerId) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                    pan = if (zoom == 1f) Offset.Zero else pan + event.calculatePan()
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (screen == null || bitmap == null) {
            Text(frame?.message ?: if (screen != null) "This Computer can't send screenshots; it's controlled by its elements." else "Waiting for the screen…", color = color(mutedColor))
            return@Box
        }
        var size by remember { mutableStateOf(IntSize.Zero) }
        fun pixel(at: Offset) = (at.x / size.width * bitmap.width).roundToInt() to (at.y / size.height * bitmap.height).roundToInt()
        Image(
            bitmap, "Screen of ${frame.computer.name}",
            Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
                .clip(Theme[shapes][mediumShape])
                .border(1.dp, color(borderColor), Theme[shapes][mediumShape])
                .then(if (!interactive) Modifier else Modifier
                    .pointerInput(bitmap.width, bitmap.height) { size = this.size; detectTapGestures { at -> pixel(at).let { (x, y) -> onTap(x, y) } } }
                    .pointerInput(bitmap.width, bitmap.height) {
                        var start = Offset.Zero
                        var end = Offset.Zero
                        detectDragGestures(onDragStart = { start = it; end = it }, onDrag = { change, delta -> change.consume(); end += delta }, onDragEnd = {
                            val (x, y) = pixel(start)
                            val (toX, toY) = pixel(end)
                            onSwipe(x, y, toX, toY)
                        })
                    }),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
        )
        if (zoom > 1f) Keycap({ zoom = 1f; pan = Offset.Zero }, Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Lucide.Shrink, Modifier.size(16.dp))
        }
    }
}

/** The user's keys and typing during a Takeover: Android's navigation buttons, or a desktop keyboard. */
@Composable
internal fun ComputerControls(platform: Platform, onKey: (String) -> Unit, onType: (String) -> Unit) {
    val text = rememberTextFieldState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color(panelColor)).border(1.dp, color(borderColor), RoundedCornerShape(18.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (platform == Platform.Android) NavigationBar(onKey) else Keyboard(platform == Platform.MacOS, onKey)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Input(text, "Type into the focused field") }
            Keycap({ onType(text.text.toString()); text.clearText() }, accent = true) { Text("Type", fontWeight = FontWeight.Medium) }
            if (platform == Platform.Android) Keycap({ onKey("enter") }) { Icon(Lucide.CornerDownLeft, Modifier.size(18.dp)) }
        }
    }
}

/** Android's own Back, Home and Recents, in its navigation bar's shapes. */
@Composable
private fun NavigationBar(onKey: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(color(backgroundColor)).padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(Triple("back", "Back", Lucide.ChevronLeft), Triple("home", "Home", Lucide.Circle), Triple("recents", "Apps", Lucide.Square)).forEach { (key, label, icon) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).clickable { onKey(key) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(icon, Modifier.size(20.dp))
                Text(label, fontSize = 11.sp, color = color(mutedColor))
            }
        }
    }
}

/** A desktop keyboard's essentials: modifiers to hold, then a key that sends them together, e.g. ⌘ and C for `cmd+c`. */
@Composable
private fun Keyboard(mac: Boolean, onKey: (String) -> Unit) {
    var held by remember { mutableStateOf(emptyList<String>()) }
    val key = rememberTextFieldState()
    fun press(name: String) { onKey((held + name).joinToString("+")); held = emptyList() }
    val modifiers = if (mac) listOf(Triple("ctrl", "⌃", "control"), Triple("option", "⌥", "option"), Triple("cmd", "⌘", "command"), Triple("shift", "⇧", "shift"))
        else listOf(Triple("ctrl", "Ctrl", null), Triple("alt", "Alt", null), Triple("cmd", "Win", null), Triple("shift", "⇧", "shift"))
    val names = modifiers.associate { (id, glyph, _) -> id to glyph }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Keycap({ press("escape") }, Modifier.weight(1f)) { KeyLabel("esc") }
            Keycap({ press("tab") }, Modifier.weight(1f)) { KeyLabel("tab") }
            Keycap({ press("delete") }, Modifier.weight(1f)) { Icon(Lucide.Delete, Modifier.size(18.dp)) }
            Keycap({ press("return") }, Modifier.weight(1.6f)) { Icon(Lucide.CornerDownLeft, Modifier.size(18.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            modifiers.forEach { (id, glyph, word) ->
                Keycap({ held = if (id in held) held - id else held + id }, Modifier.weight(1f), active = id in held) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (word == null) KeyLabel(glyph) else Text(glyph, fontSize = 16.sp, lineHeight = 18.sp)
                        word?.let { Text(it, fontSize = 9.sp, lineHeight = 11.sp, color = if (id in held) color(onAccentColor) else color(mutedColor)) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            Keycap({ press("space") }, Modifier.weight(1f)) { KeyLabel("space") }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Keycap({ press("up") }, Modifier.width(40.dp), height = 30.dp) { Icon(Lucide.ArrowUp, Modifier.size(14.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Keycap({ press("left") }, Modifier.width(40.dp), height = 30.dp) { Icon(Lucide.ArrowLeft, Modifier.size(14.dp)) }
                    Keycap({ press("down") }, Modifier.width(40.dp), height = 30.dp) { Icon(Lucide.ArrowDown, Modifier.size(14.dp)) }
                    Keycap({ press("right") }, Modifier.width(40.dp), height = 30.dp) { Icon(Lucide.ArrowRight, Modifier.size(14.dp)) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Input(key, if (held.isEmpty()) "Any key, e.g. c or f5" else "Key to press with ${held.joinToString(" ") { names.getValue(it) }}") }
            Keycap({ key.text.toString().trim().lowercase().takeIf { it.isNotEmpty() }?.let(::press); key.clearText() }, active = held.isNotEmpty()) {
                Text(if (held.isEmpty()) "Press" else held.joinToString(" ") { names.getValue(it) } + " +", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun KeyLabel(text: String) = Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)

/** A key: raised by a darker bottom edge; [active] for a held modifier, [accent] for the main action. */
@Composable
private fun Keycap(onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, accent: Boolean = false, height: Dp = 44.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val face = when { active || accent -> color(accentColor); else -> color(backgroundColor) }
    val edge = if (active || accent) color(accentColor).copy(alpha = 0.6f) else color(borderColor)
    Box(
        modifier.height(height).defaultMinSize(minWidth = 44.dp).clip(shape).background(edge).padding(bottom = 2.dp).clip(shape).background(face)
            .border(1.dp, edge, shape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ProvideContentColor(if (active || accent) color(onAccentColor) else color(onBackgroundColor)) { content() }
    }
}
