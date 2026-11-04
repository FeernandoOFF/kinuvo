package kinuvo.ui.screen

import kinuvo.ui.navigation.PageHeader
import kinuvo.ui.navigation.PageAction
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.screen.*
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kinuvo.ui.session.*
import kotlinx.coroutines.*
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

@Composable
fun LiveViewScreen(model: LiveViewModel, back: (() -> Unit)?, docked: Boolean = false, extra: List<PageAction> = emptyList()) {
    val state by model.state.collectAsState()
    DisposableEffect(model) { model.visible(true); onDispose { model.visible(false) } }
    LiveViewContent(state, LiveActions(model::select, model::takeover, model::open, model::command,
        model::input, model::drawn, model::decodeFailed, model::retry, model::showOverview, model::close, model::resize, model::presentation), back, docked, extra)
}

internal class LiveActions(
    val select: (ScreenRef) -> Unit = {}, val takeover: (Boolean, Boolean) -> Unit = { _, _ -> },
    val open: (String) -> Unit = {}, val command: (ScreenCommandKind, String?) -> Unit = { _, _ -> },
    val input: (ScreenFrame, List<ScreenAction>) -> Unit = { _, _ -> }, val drawn: (ScreenFrame) -> Unit = {},
    val decodeFailed: () -> Unit = {}, val retry: () -> Unit = {}, val overview: (List<ScreenRef>) -> Unit = {},
    val close: (ScreenRef) -> Unit = {}, val resize: (Int, Int) -> Unit = { _, _ -> }, val presentation: (Boolean, Boolean) -> Unit = { _, _ -> },
) {
    fun copy(select: (ScreenRef) -> Unit) = LiveActions(select, takeover, open, command, input, drawn, decodeFailed, retry, overview, close, resize, presentation)
}

/**
 * One layout for every Screen: a Browser's tabs and address above, a Computer list beside on wide windows, the Screen
 * framed by who controls it, and takeover below. [docked] trims the header for the panel beside a chat.
 */
@Composable
internal fun LiveViewContent(state: LiveViewState, actions: LiveActions, back: (() -> Unit)?, docked: Boolean = false, extra: List<PageAction> = emptyList()) {
    var keyboard by remember(state.selected, state.control.generation) { mutableStateOf(false) }
    val keyboardFocus = remember { FocusRequester() }
    val tabs = state.owner != null

    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor)).then(if (docked) Modifier else Modifier.safeDrawingPadding()).imePadding()) {
        if (!docked && maxWidth < 760.dp) {
            MobileLiveViewContent(state, actions, back)
            return@BoxWithConstraints
        }
        val wide = maxWidth >= 760.dp
        var overview by rememberSaveable(wide, tabs) { mutableStateOf(false) }
        val headerActions = buildList {
            if (state.error != null || !state.connected) add(PageAction(Lucide.RefreshCw, "Reconnect", actions.retry))
            if (tabs) add(PageAction(Lucide.LayoutGrid, "Screens", { overview = !overview }))
            addAll(extra)
        }
        Column(Modifier.fillMaxSize()) {
            if (!tabs && back == null && !docked) PageHeader(if (tabs) Lucide.Globe else Lucide.Monitor, if (tabs) "Browser" else "Computers",
                subtitle = state.screen?.title, actions = headerActions)
            else {
                TitleBarRow(leading = !docked) {
                    back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
                    Icon(if (tabs) Lucide.Globe else Lucide.Monitor, Modifier.padding(start = 6.dp).size(16.dp), tint = color(mutedColor))
                    Text(if (tabs) "Browser" else state.screen?.title ?: "Computers", Modifier.padding(start = 4.dp), singleLine = true)
                    Spacer(Modifier.weight(1f))
                    if (state.presentation != null) BrowserWindowControls(state, actions)
                    headerActions.forEach { HeaderButton(it.icon, it.label, it.onClick) }
                }
                HorizontalSeparator()
            }
            if (tabs) TabStrip(state, actions)
            state.screen?.takeIf { it.capabilities.navigation }?.let { AddressBar(state, actions) }
            Banner(state)
            if (docked && !wide && overview) ScreenOverview(state, actions.copy(select = { actions.select(it); overview = false }), Modifier.weight(1f).fillMaxWidth(), grid = true)
            else Row(Modifier.weight(1f)) {
                if (wide && overview && tabs) {
                    ScreenOverview(state, actions, Modifier.width(240.dp).fillMaxHeight())
                    VerticalSeparator()
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val screen = state.screen
                    if (screen == null) EmptyStage(state, actions, Modifier.weight(1f).fillMaxWidth())
                    else {
                        Stage(state, screen, actions, Modifier.weight(1f).fillMaxWidth().padding(if (wide) 12.dp else 8.dp),
                            onFocus = { if (state.interactive && "type" in screen.capabilities.inputs) keyboardFocus.requestFocus() })
                        if (state.ownsControl && "type" in screen.capabilities.inputs) key(state.selected, state.control.generation) {
                            RemoteKeyboard(state, actions, keyboardFocus, keyboard)
                        }
                        ControlBar(state, actions, keyboard, { keyboard = !keyboard }, Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
        if (!wide && !docked && tabs) BottomSheet(overview, onDismissRequest = { overview = false }, header = { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Screens · ${state.screens.size}", Modifier.weight(1f))
            if (tabs) Button(onClick = { actions.open("about:blank") }, enabled = state.connected, style = ButtonStyle.Ghost) { Text("New tab") }
        } }) {
            ScreenOverview(state, actions.copy(select = { actions.select(it); overview = false }), Modifier.weight(1f), grid = true)
        }
    }
}

private enum class Status(val label: String, val tone: Tone) {
    Offline("Disconnected", Tone.Neutral), Suspended("Takeover suspended", Tone.Warning), Yours("You’re in control", Tone.Success),
    Elsewhere("Another Device has control", Tone.Warning), Needed("Needs you", Tone.Warning),
    Working("Agent is working", Tone.Accent), Watching("Watching", Tone.Neutral), Unavailable("Unavailable", Tone.Danger),
}

private fun ScreenInfo.status(connected: Boolean, device: String?, reason: String?) = when {
    !connected -> Status.Offline
    control.suspended -> Status.Suspended
    control.holder != null && control.holder == device -> Status.Yours
    control.holder != null -> Status.Elsewhere
    reason != null -> Status.Needed
    status != ScreenStatus.Ready -> Status.Unavailable
    active -> Status.Working
    else -> Status.Watching
}

private val LiveViewState.status get() = screen?.status(connected, device, requestReason)

@Composable
internal fun TabStrip(state: LiveViewState, actions: LiveActions) {
    Row(Modifier.fillMaxWidth().background(color(sidebarColor)).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        state.screens.forEach { screen ->
            val selected = screen.ref == state.selected
            val yours = state.connected && screen.control.holder == state.device && state.device != null &&
                !screen.control.suspended && !screen.control.draining && !screen.control.closed
            val paused = state.connected && screen.control.holder == state.device && state.device != null && screen.control.suspended
            val tabColor = if (yours) Tone.Success.color() else if (paused) Tone.Warning.color() else color(borderColor)
            Row(Modifier.semantics { stateDescription = if (yours) "You’re in control" else if (paused) "Takeover suspended" else if (selected) "Selected" else "Watching" }
                .widthIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(if (yours || paused) tabColor.copy(alpha = if (selected) 0.16f else 0.08f) else if (selected) color(panelColor) else Color.Transparent)
                .then(if (selected || yours || paused) Modifier.border(1.dp, tabColor, RoundedCornerShape(8.dp)) else Modifier)
                .clickable(onClickLabel = screen.title) { actions.select(screen.ref) }.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (yours || paused) Icon(Lucide.Hand, Modifier.size(13.dp), tint = tabColor)
                else if (screen.active) Spinner(size = 12.dp) else Icon(screen.icon(), Modifier.size(13.dp), tint = color(mutedColor))
                Text(screen.title.ifBlank { "New tab" }, Modifier.weight(1f, fill = false), fontSize = 13.sp, singleLine = true, overflow = TextOverflow.Ellipsis,
                    color = if (selected) Color.Unspecified else color(mutedColor))
                if (screen.capabilities.tabs)
                    IconButton(onClick = { actions.close(screen.ref) }, enabled = state.connected && !state.pending, style = ButtonStyle.Ghost, modifier = Modifier.size(18.dp)) {
                        Icon(Lucide.X, Modifier.size(12.dp), contentDescription = "Close ${screen.title.ifBlank { "tab" }}")
                    }
            }
        }
        HeaderButton(Lucide.Plus, "New tab", { actions.open("about:blank") }, enabled = state.connected && !state.pending)
    }
    HorizontalSeparator()
}

@Composable
internal fun AddressBar(state: LiveViewState, actions: LiveActions) {
    var address by remember(state.selected, state.screen?.currentUrl) { mutableStateOf(state.screen?.currentUrl.orEmpty()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HeaderButton(Lucide.ArrowLeft, "Go back", { actions.command(ScreenCommandKind.Back, null) }, enabled = state.interactive)
        HeaderButton(Lucide.ArrowRight, "Go forward", { actions.command(ScreenCommandKind.Forward, null) }, enabled = state.interactive)
        HeaderButton(Lucide.RotateCw, "Reload", { state.screen?.currentUrl?.let { actions.command(ScreenCommandKind.Navigate, it) } }, enabled = state.interactive)
        Row(Modifier.weight(1f).clip(RoundedCornerShape100).background(color(panelColor)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (address.startsWith("https://")) Lucide.Lock else Lucide.Globe, Modifier.size(13.dp), tint = color(mutedColor))
            androidx.compose.foundation.text.BasicTextField(address, { address = it }, Modifier.weight(1f).semantics { contentDescription = "Address" },
                enabled = state.interactive, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = color(mutedColor), fontSize = 13.sp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { actions.command(ScreenCommandKind.Navigate, address) }))
        }
    }
}

@Composable
internal fun Banner(state: LiveViewState) {
    val (text, tone) = state.error?.let { it to Tone.Danger } ?: when {
        !state.connected -> "Disconnected · Takeover stays paused until explicit handback" to Tone.Neutral
        state.requestReason != null -> "The Agent needs you: ${state.requestReason}" to Tone.Warning
        else -> return
    }
    Pill(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), tone, if (tone == Tone.Warning) Lucide.Hand else null)
}

@Composable
internal fun EmptyStage(state: LiveViewState, actions: LiveActions, modifier: Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.pending || !state.loaded) Spinner(size = 20.dp) else Icon(if (state.owner != null) Lucide.Globe else Lucide.Monitor, Modifier.size(28.dp), tint = color(mutedColor))
        Text(if (state.pending) "Preparing Browser…" else if (state.loaded) "No screens yet. Ask the Agent to browse or connect a Computer." else "Finding screens…",
            color = color(mutedColor), textAlign = TextAlign.Center)
        if (state.owner != null) Button(onClick = { actions.open("about:blank") }, enabled = state.connected && !state.pending) { Text("Open Browser") }
    }
}

@Composable
private fun Stage(state: LiveViewState, screen: ScreenInfo, actions: LiveActions, modifier: Modifier, onFocus: () -> Unit) {
    val status = state.status ?: Status.Watching
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val widthDp = maxWidth.value.roundToInt().coerceIn(240, 2560)
        val heightDp = maxHeight.value.roundToInt().coerceIn(160, 2160)
        LaunchedEffect(screen.ref, state.control.generation, state.ownsControl, state.pending, widthDp, heightDp) {
            if (state.ownsControl && screen.capabilities.resize && !state.pending) {
                delay(180)
                actions.resize(widthDp, heightDp)
            }
        }
        val ratio = state.frame?.sample?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1.6f
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        Box(Modifier.width(width).height(height).clip(shape).background(color(sidebarColor))
            .border(if (status == Status.Yours) 2.dp else 1.dp, if (status == Status.Watching || status == Status.Offline) color(borderColor) else status.tone.color(), shape)) {
            LiveCanvas(state.frame, state.interactive, actions.input, actions.drawn, actions.decodeFailed,
                Modifier.fillMaxSize(), state.control.generation, "scroll" in screen.capabilities.inputs, onFocus)
            if (!state.connected) state.frameReceivedAt?.let { Text("Last frame · $it", Modifier.align(Alignment.BottomStart).padding(8.dp), fontSize = 12.sp, color = color(mutedColor)) }
        }
    }
}

@Composable
private fun ControlBar(state: LiveViewState, actions: LiveActions, keyboard: Boolean, onKeyboard: () -> Unit, modifier: Modifier) {
    val control = state.control
    val status = state.status ?: Status.Watching
    Row(modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Pill(status.label, tone = status.tone, icon = if (status == Status.Yours) Lucide.Hand else if (status == Status.Working) null else Lucide.Eye, loading = status == Status.Working) }
        if (state.ownsControl) HeaderButton(Lucide.Keyboard, if (keyboard) "Hide keyboard" else "Keyboard", onKeyboard)
        if (control.holder != null && !state.ownsControl) Button(onClick = { actions.takeover(false, false) },
            enabled = state.connected && !state.pending && !control.draining, style = ButtonStyle.Ghost) { Text("Hand back") }
        Button(onClick = { actions.takeover(!state.ownsControl, control.holder != null) },
            enabled = state.connected && !state.pending && !control.draining, style = if (state.ownsControl) ButtonStyle.Outlined else ButtonStyle.Primary) {
            Text(when { state.pending || control.draining -> "Waiting…"; state.ownsControl -> "Hand back to Agent"; control.holder != null -> "Take over here"; else -> "Take over" })
        }
    }
}

@Composable
internal fun ScreenOverview(state: LiveViewState, actions: LiveActions, modifier: Modifier = Modifier, grid: Boolean = false) {
    val list = rememberLazyGridState()
    val visible by rememberUpdatedState(actions.overview)
    LaunchedEffect(list, state.screens.map { it.ref }) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { state.screens.getOrNull(it.index)?.ref } }
            .distinctUntilChanged().collect { visible(it) }
    }
    DisposableEffect(Unit) { onDispose { visible(emptyList()) } }
    LazyVerticalGrid(GridCells.Fixed(if (grid) 2 else 1), modifier.background(color(sidebarColor)), state = list, contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.screens, key = { "${it.ref.source}/${it.ref.owner}/${it.ref.id}" }) { screen ->
            val selected = screen.ref == state.selected
            val status = screen.status(state.connected, state.device, state.requestReason.takeIf { selected })
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) color(panelColor) else Color.Transparent)
                .border(1.dp, color(if (selected) accentColor else borderColor), RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Select ${screen.title}") { actions.select(screen.ref) }.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveCanvas(if (selected) state.frame else state.thumbnails[screen.ref], false,
                    { _, _ -> }, actions.drawn, {}, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(6.dp)).background(color(backgroundColor)), gestures = false)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(screen.icon(), Modifier.size(13.dp), tint = color(mutedColor))
                    Text(screen.title, Modifier.weight(1f), fontSize = 13.sp, singleLine = true, overflow = TextOverflow.Ellipsis)
                    if (screen.capabilities.tabs) IconButton(onClick = { actions.close(screen.ref) }, enabled = state.connected && !state.pending, style = ButtonStyle.Ghost, modifier = Modifier.size(20.dp)) {
                        Icon(Lucide.X, Modifier.size(12.dp), contentDescription = "Close ${screen.title.ifBlank { "tab" }}")
                    }
                }
                Pill(status.label, tone = status.tone, loading = status == Status.Working)
            }
        }
        if (!grid && state.owner != null) item { Button(onClick = { actions.open("about:blank") }, enabled = state.connected, style = ButtonStyle.Ghost) { Text("New tab") } }
    }
}

private data class CanvasBinding(val screen: ScreenRef?, val subscription: String?, val epoch: String?, val generation: Long, val interactive: Boolean)

private data class DecodedFrame(val frame: ScreenFrame, val bitmap: ImageBitmap)

@Composable
internal fun LiveCanvas(frame: ScreenFrame?, interactive: Boolean, input: (ScreenFrame, List<ScreenAction>) -> Unit,
    drawn: (ScreenFrame) -> Unit, failed: () -> Unit, modifier: Modifier = Modifier, generation: Long = 0, scroll: Boolean = false, onFocus: () -> Unit = {}, trackpad: Boolean = false, recenter: Int = 0, gestures: Boolean = true) {
    var decoded by remember(frame?.subscription, frame?.epoch) { mutableStateOf<DecodedFrame?>(null) }
    LaunchedEffect(frame) {
        frame ?: return@LaunchedEffect
        if (frame.sample.width <= 0 || frame.sample.height <= 0) { failed(); return@LaunchedEffect }
        val bitmap = withContext(Dispatchers.Default) { runCatching { attachmentImage(Base64.decode(frame.sample.image), fullSize = true) }.getOrNull() }
        if (bitmap == null) failed() else decoded = DecodedFrame(frame, bitmap)
    }
    var gesture by remember(frame?.screen, frame?.subscription, frame?.epoch, generation, interactive) { mutableStateOf<DecodedFrame?>(null) }
    var zoom by remember(frame?.subscription) { mutableStateOf(1f) }
    var pan by remember(frame?.subscription) { mutableStateOf(Offset.Zero) }
    var painted by remember(frame?.subscription) { mutableStateOf<ScreenFrame?>(null) }
    LaunchedEffect(painted) { painted?.let(drawn) }
    val binding = CanvasBinding(frame?.screen, frame?.subscription, frame?.epoch, generation, interactive)
    val latestBinding by rememberUpdatedState(binding)
    val blank = remember { ImageBitmap(1, 1) }
    var pointer by remember(frame?.screen, frame?.epoch, generation, frame?.sample?.width, frame?.sample?.height) { mutableStateOf(RemotePointer()) }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val shown = gesture ?: decoded
        val latest by rememberUpdatedState(shown)
        val ratio = shown?.bitmap?.let { it.width.toFloat() / it.height } ?: 1.6f
        val imageWidth = minOf(maxWidth, maxHeight * ratio)
        val imageHeight = imageWidth / ratio
        val density = androidx.compose.ui.platform.LocalDensity.current
        val imageWidthPx = with(density) { imageWidth.toPx() }
        val imageHeightPx = with(density) { imageHeight.toPx() }
        LaunchedEffect(recenter) { pointer = RemotePointer.centered(imageWidthPx, imageHeightPx, zoom, pan.x, pan.y) }
        val surfaceWidth = with(density) { maxWidth.toPx() }
        val surfaceHeight = with(density) { maxHeight.toPx() }
        LaunchedEffect(imageWidthPx, imageHeightPx, surfaceWidth, surfaceHeight, zoom) {
            val mx = ((imageWidthPx * zoom - surfaceWidth) / 2).coerceAtLeast(0f)
            val my = ((imageHeightPx * zoom - surfaceHeight) / 2).coerceAtLeast(0f)
            pan = Offset(pan.x.coerceIn(-mx, mx), pan.y.coerceIn(-my, my))
        }
        if (shown == null) Text("Waiting for a fresh screen…", color = color(mutedColor))
        Box(Modifier.fillMaxSize().then(if (gestures) Modifier.pointerInput(binding, trackpad) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val shown = latest ?: return@awaitEachGesture
                val started = latestBinding
                if (shown.frame.screen != started.screen || shown.frame.subscription != started.subscription || shown.frame.epoch != started.epoch) return@awaitEachGesture
                gesture = shown
                val bounds = size
                val iw = minOf(bounds.width.toFloat(), bounds.height * shown.frame.sample.width.toFloat() / shown.frame.sample.height)
                val ih = iw * shown.frame.sample.height / shown.frame.sample.width
                try {
                    var multi = false
                    var pinching = false
                    var accumulatedZoom = 1f
                    var scrollY = 0f
                    var travel = 0f
                    var last = down.position
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val delta = change.position - change.previousPosition
                        travel += delta.getDistance()
                        if (event.changes.count { it.pressed } >= 2) {
                            multi = true
                            val factor = event.calculateZoom()
                            accumulatedZoom *= factor
                            if (kotlin.math.abs(accumulatedZoom - 1f) > 0.04f) pinching = true
                            if (!trackpad || pinching) {
                                zoom = (zoom * factor).coerceIn(1f, 6f)
                                val moved = if (zoom == 1f) Offset.Zero else pan + event.calculatePan()
                                val mx = ((iw * zoom - bounds.width) / 2).coerceAtLeast(0f)
                                val my = ((ih * zoom - bounds.height) / 2).coerceAtLeast(0f)
                                pan = Offset(moved.x.coerceIn(-mx, mx), moved.y.coerceIn(-my, my))
                            } else scrollY += event.calculatePan().y
                        } else if (!multi && trackpad && started.interactive) {
                            pointer = pointer.move(delta.x, delta.y, iw, ih, zoom)
                            if (zoom > 1f) pan = Offset(pointer.followingPan(iw, bounds.width.toFloat(), zoom, true),
                                pointer.followingPan(ih, bounds.height.toFloat(), zoom, false))
                        }
                        last = change.position
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (started.interactive && started == latestBinding) {
                        val action = when {
                            multi && trackpad && !pinching && scroll && kotlin.math.abs(scrollY) > 12.dp.toPx() ->
                                ScreenAction(ScreenActionKind.Scroll, direction = if (scrollY > 0) "up" else "down")
                            multi -> null
                            trackpad -> if (travel < 12.dp.toPx()) pointer.tap(shown.frame.sample.width, shown.frame.sample.height) else null
                            else -> {
                                val point = RemotePointer.at(last.x, last.y, bounds.width.toFloat(), bounds.height.toFloat(), iw, ih, zoom, pan.x, pan.y)
                                when {
                                    point == null -> null
                                    (last - down.position).getDistance() < 12.dp.toPx() -> point.tap(shown.frame.sample.width, shown.frame.sample.height)
                                    scroll -> ScreenAction(ScreenActionKind.Scroll, direction = if (last.y > down.position.y) "up" else "down")
                                    else -> RemotePointer.at(down.position.x, down.position.y, bounds.width.toFloat(), bounds.height.toFloat(), iw, ih, zoom, pan.x, pan.y)
                                        ?.tap(shown.frame.sample.width, shown.frame.sample.height)?.let { start ->
                                            val end = point.tap(shown.frame.sample.width, shown.frame.sample.height)
                                            ScreenAction(ScreenActionKind.Swipe, x = start.x, y = start.y, toX = end.x, toY = end.y)
                                        }
                                }
                            }
                        }
                        if (action != null) { input(shown.frame, listOf(action)); onFocus() }
                    }
                } finally { gesture = null }
            }
        } else Modifier))
        Box(Modifier.width(imageWidth).height(imageHeight).graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }) {
            Image(shown?.bitmap ?: blank, if (shown == null) null else "Live screen", Modifier.fillMaxSize()
                .drawWithContent { drawContent(); if (shown != null && painted != shown.frame) painted = shown.frame }, contentScale = ContentScale.Fit)
            if (trackpad && interactive && shown != null) Box(
                Modifier.offset {
                    val tip = with(density) { (22.dp / zoom).toPx() } / 6f
                    androidx.compose.ui.unit.IntOffset((pointer.x * imageWidthPx - tip).roundToInt(), (pointer.y * imageHeightPx - tip).roundToInt())
                }
                    .size(22.dp / zoom).semantics { contentDescription = "Local pointer" }) {
                Icon(Lucide.MousePointer2, Modifier.fillMaxSize().offset(1.dp / zoom, 1.dp / zoom), tint = Color.Black)
                Icon(Lucide.MousePointer2, Modifier.fillMaxSize(), tint = Color.White)
            }
        }
        if (zoom > 1f) Button(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.align(Alignment.TopEnd)) { Text("${(zoom * 100).roundToInt()}% · Fit") }
    }
}

@Composable
internal fun RemoteKeyboard(state: LiveViewState, actions: LiveActions, focus: FocusRequester, expanded: Boolean, compact: Boolean = false) {
    val latest by rememberUpdatedState(state)
    val send by rememberUpdatedState<(List<ScreenAction>) -> Unit>({ input ->
        latest.displayed?.takeIf { latest.interactive }?.let { actions.input(it, input) }
    })
    val software = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(RemoteInputAnchor, TextRange(RemoteInputAnchor.length))) }
    LaunchedEffect(expanded, state.interactive) {
        if (expanded && state.interactive) { focus.requestFocus(); software?.show() }
        else if (!expanded) { software?.hide(); if (compact) focusManager.clearFocus() }
    }
    DisposableEffect(Unit) { onDispose { software?.hide() } }
    LaunchedEffect(state.interactive, state.resizing) {
        if (state.interactive && value.composition == null && value.text != RemoteInputAnchor) {
            val edit = remoteTextEdit(value.text, false)
            value = TextFieldValue(edit.text, TextRange(edit.text.length))
            if (edit.actions.isNotEmpty()) send(edit.actions)
        } else if (!state.interactive && !state.resizing) value = TextFieldValue(RemoteInputAnchor, TextRange(RemoteInputAnchor.length))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(value, { next ->
            if (latest.interactive) {
                val edit = remoteTextEdit(next.text, next.composition != null)
                value = if (next.composition != null) next else TextFieldValue(edit.text, TextRange(edit.text.length))
                if (edit.actions.isNotEmpty()) send(edit.actions)
            } else if (latest.resizing) value = next
        }, Modifier.fillMaxWidth().height(if (expanded && !compact) 42.dp else 1.dp).clipToBounds().graphicsLayer { alpha = if (expanded && !compact) 1f else 0f }
            .focusRequester(focus).onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) value = TextFieldValue(RemoteInputAnchor, TextRange(RemoteInputAnchor.length))
            }.onPreviewKeyEvent { event ->
                val key = remoteKey(event, latest.screen?.capabilities ?: return@onPreviewKeyEvent false) ?: return@onPreviewKeyEvent false
                if (latest.interactive && event.type == KeyEventType.KeyDown) send(listOf(ScreenAction(ScreenActionKind.Key, key = key)))
                true
            }.background(color(panelColor), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = if (expanded) 10.dp else 0.dp)
            .semantics { contentDescription = "Type into remote screen" },
            enabled = state.connected && state.ownsControl && (!compact || expanded), singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = color(mutedColor)),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go, showKeyboardOnFocus = false),
            keyboardActions = KeyboardActions(onGo = { send(listOf(ScreenAction(ScreenActionKind.Key, key = "Enter"))) }),
            decorationBox = { field ->
                Box { if (expanded && !compact && value.text == RemoteInputAnchor) Text("Type directly into the page…", color = color(mutedColor), fontSize = 13.sp); field() }
            })
        if (expanded && !compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val keys = listOf("Tab" to Lucide.ArrowRightToLine, "Escape" to Lucide.LogOut, "Backspace" to Lucide.Delete,
                    "ArrowLeft" to Lucide.ArrowLeft, "ArrowUp" to Lucide.ArrowUp, "ArrowDown" to Lucide.ArrowDown,
                    "ArrowRight" to Lucide.ArrowRight, "Enter" to Lucide.CornerDownLeft)
                keys.filter { it.first in state.screen!!.capabilities.keys }.forEach { (key, icon) ->
                    HeaderButton(icon, key, { send(listOf(ScreenAction(ScreenActionKind.Key, key = key))); focus.requestFocus() }, enabled = state.interactive)
                }
            }
            Text(if (focused) "Typing into the focused field on the page" else "Tap the page field, then type here", fontSize = 12.sp, color = color(mutedColor))
        }
    }
}

internal fun remoteKey(event: KeyEvent, capabilities: ScreenCapabilities): String? {
    val key = when (event.key) {
        Key.Tab -> "Tab"; Key.Escape -> "Escape"; Key.Backspace -> "Backspace"; Key.Delete -> "Delete"
        Key.DirectionLeft -> "ArrowLeft"; Key.DirectionRight -> "ArrowRight"; Key.DirectionUp -> "ArrowUp"; Key.DirectionDown -> "ArrowDown"
        Key.Enter, Key.NumPadEnter -> "Enter"; Key.MoveHome -> "Home"; Key.MoveEnd -> "End"; Key.PageUp -> "PageUp"; Key.PageDown -> "PageDown"
        Key.A -> "A"; Key.C -> "C"; Key.X -> "X"; Key.Z -> "Z"; Key.Y -> "Y"
        else -> return null
    }
    if (key !in capabilities.keys) return null
    if (key.length == 1 && (event.isAltPressed || (!event.isCtrlPressed && !event.isMetaPressed))) return null
    val modifiers = buildList {
        if (event.isCtrlPressed) add("Control")
        if (event.isMetaPressed) add("Meta")
        if (event.isAltPressed) add("Alt")
        if (event.isShiftPressed) add("Shift")
    }
    if (modifiers.any { it !in capabilities.modifiers }) return null
    return (modifiers + key).joinToString("+")
}

/** The Computers Agents can control: a list, opening one to its Live view, beside the list on wide windows. */
@Composable
fun ComputersScreen(model: LiveViewModel, list: Boolean, onOpen: (ScreenRef) -> Unit, back: (() -> Unit)?) {
    val state by model.state.collectAsState()
    val actions = LiveActions(model::select, model::takeover, model::open, model::command, model::input, model::drawn, model::decodeFailed, model::retry, model::showOverview, model::close, model::resize, model::presentation)
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        if (maxWidth >= 900.dp) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(340.dp).fillMaxHeight().background(color(sidebarColor))) { ComputerList(state, actions.retry, actions.select, selected = state.selected) }
            VerticalSeparator()
            Box(Modifier.weight(1f)) {
                DisposableEffect(model) { model.visible(true); onDispose { model.visible(false) } }
                if (state.screen != null) LiveViewContent(state, actions, null, docked = true)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (state.loaded) "Pick a Computer to watch it." else "Finding Computers…", color = color(mutedColor)) }
            }
        } else if (list) ComputerList(state, actions.retry, onOpen)
        else LiveViewScreen(model, back)
    }
}

@Composable
internal fun ComputerList(state: LiveViewState, onRetry: () -> Unit, onOpen: (ScreenRef) -> Unit, selected: ScreenRef? = null) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(Lucide.MonitorSmartphone, "Computers")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                !state.loaded && state.error != null -> {
                    ErrorMessage(state.error!!)
                    RowGroup { ActionRow("Retry", Lucide.RefreshCw, onRetry) }
                }
                !state.loaded -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
                state.screens.isEmpty() -> Text("No Computers yet. Allow computer use on a phone or computer under Settings → This Device's tools, and turn on computer use in an Agent's tools.",
                    Modifier.padding(horizontal = 6.dp), color = color(mutedColor))
                else -> RowGroup {
                    state.screens.forEach { screen ->
                        val status = screen.status(state.connected, state.device, state.requestReason.takeIf { screen.ref == state.selected })
                        GroupRow(screen.title, { onOpen(screen.ref) }, status.label, screen.icon(), selected = screen.ref == selected) {
                            when (status) {
                                Status.Working -> Pill("Working", tone = Tone.Accent, loading = true)
                                Status.Needed, Status.Elsewhere, Status.Suspended -> Pill("Needs you", tone = Tone.Warning)
                                Status.Yours -> Pill("You", tone = Tone.Success)
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BrowserWindowControls(state: LiveViewState, actions: LiveActions) {
    if (state.presentation == null) return
    var expanded by remember { mutableStateOf(false) }
    var restartTo by remember(state.owner) { mutableStateOf<Boolean?>(null) }
    DropdownMenu(expanded = expanded, onExpandedChange = { expanded = it }, panel = {
        DropdownMenuPanel {
            BrowserPresentationMenuItems(state) { expanded = false; restartTo = it }
        }
    }) {
        HeaderButton(Lucide.Ellipsis, "Browser options", { expanded = true })
    }
    BrowserRestartDialog(restartTo, state, actions) { restartTo = null }
}

@Composable
internal fun DropdownMenuPanelContentScope.BrowserPresentationMenuItems(state: LiveViewState, restart: (Boolean) -> Unit) {
    val settings = state.presentation ?: return
    DropdownMenuItem(onClick = { restart(!settings.headless) }, enabled = state.connected && !state.pending) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (!settings.headless) Lucide.Check else Lucide.MonitorOff, Modifier.size(18.dp))
            Text("Show browser window")
        }
    }
}

@Composable
internal fun BrowserRestartDialog(target: Boolean?, state: LiveViewState, actions: LiveActions, dismiss: () -> Unit) {
    target?.let { headless ->
        Dialog(onDismissRequest = dismiss) {
            Panel(Modifier.widthIn(max = 380.dp).semantics { paneTitle = "Restart Chrome" }, contentPadding = PaddingValues(20.dp)) {
                Text(if (headless) "Hide browser window?" else "Show browser window?")
                Spacer(Modifier.height(8.dp))
                Text("Chrome will restart. Unsaved form data and live page state will be lost. Tabs will reopen and saved logins will be kept.")
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(dismiss, style = ButtonStyle.Secondary) { Text("Cancel") }
                    Button({ dismiss(); actions.presentation(headless, false) }, enabled = state.connected && !state.pending) { Text("Restart Chrome") }
                }
            }
        }
    }
}

/** A Browser tab, a phone (it has Recents) or a computer. */
internal fun ScreenInfo.icon() = when {
    ref.owner != null -> Lucide.Globe
    "Recents" in capabilities.keys -> Lucide.Smartphone
    else -> Lucide.Monitor
}
