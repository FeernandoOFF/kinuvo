package kinuvo.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.*
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
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
fun LiveViewScreen(model: LiveViewModel, back: (() -> Unit)?) {
    val state by model.state.collectAsState()
    DisposableEffect(model) { model.visible(true); onDispose { model.visible(false) } }
    LiveViewContent(state, LiveActions(model::select, model::takeover, model::open, model::command,
        model::input, model::drawn, model::decodeFailed, model::retry, model::showOverview), back)
}

internal class LiveActions(
    val select: (ScreenRef) -> Unit = {}, val takeover: (Boolean, Boolean) -> Unit = { _, _ -> },
    val open: (String) -> Unit = {}, val command: (ScreenCommandKind, String?) -> Unit = { _, _ -> },
    val input: (ScreenFrame, List<ScreenAction>) -> Unit = { _, _ -> }, val drawn: (ScreenFrame) -> Unit = {},
    val decodeFailed: () -> Unit = {}, val retry: () -> Unit = {}, val overview: (List<ScreenRef>) -> Unit = {},
)

@Composable
internal fun LiveViewContent(state: LiveViewState, actions: LiveActions, back: (() -> Unit)?) {
    var overview by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    val address = rememberTextFieldState()
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor)).safeDrawingPadding().imePadding()) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize()) {
            TitleBarRow(trailing = false) {
                back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
                Text("Live view", Modifier.weight(1f))
                if (state.owner != null) HeaderButton(Lucide.Plus, "New Browser tab", { actions.open("about:blank") })
                HeaderButton(Lucide.RefreshCw, "Refresh Live view", actions.retry)
                HeaderButton(Lucide.LayoutGrid, "Screens", { overview = !overview })
            }
            state.requestReason?.let { Text("The Agent needs you: $it", Modifier.padding(12.dp)) }
            state.selected?.owner?.let { Text("Agent ${it.agentId} · Session ${it.sessionId}", Modifier.padding(horizontal = 12.dp), maxLines = 1, color = color(mutedColor)) }
            state.error?.let { ErrorMessage(it, Modifier.padding(12.dp)) }
            if (!state.connected) Text("Disconnected · Takeover stays paused until explicit handback", Modifier.padding(12.dp))
            Row(Modifier.weight(1f)) {
                if (wide) ScreenOverview(state, actions, Modifier.width(200.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().padding(if (wide) 16.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val screen = state.screen
                    if (screen == null) {
                        Text(if (state.pending) "Preparing Browser…" else if (state.loaded) "No screens yet. Ask the Agent to browse or connect a Computer." else "Finding screens…", Modifier.padding(16.dp))
                        if (state.owner != null) Button(onClick = { actions.open("about:blank") }, enabled = state.connected && !state.pending) { Text("Open Browser") }
                    } else {
                        Text(screen.title, maxLines = 1)
                        ControlBar(state, actions)
                        if (screen.capabilities.navigation) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.weight(1f)) { Input(address, "https://… or a private site") }
                            Button(onClick = { actions.command(ScreenCommandKind.Navigate, address.text.toString()) }, enabled = state.interactive) { Text("Go") }
                        }
                        LiveCanvas(state.frame, state.interactive, actions.input, actions.drawn, actions.decodeFailed, Modifier.weight(1f).fillMaxWidth())
                        Text(if (screen.capabilities.delivery == ScreenDelivery.Continuous) "Live · pinch to zoom, two fingers to pan" else "Refreshes every second · pinch to zoom", color = color(mutedColor))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if ("key" in screen.capabilities.inputs || "type" in screen.capabilities.inputs)
                                Button(onClick = { keyboard = !keyboard }, style = ButtonStyle.Outlined) { Text("Keyboard") }
                            if ("scroll" in screen.capabilities.inputs) {
                                Button(onClick = { state.displayed?.let { actions.input(it, listOf(ScreenAction(ScreenActionKind.Scroll, direction = "up"))) } }, enabled = state.interactive) { Text("Scroll up") }
                                Button(onClick = { state.displayed?.let { actions.input(it, listOf(ScreenAction(ScreenActionKind.Scroll, direction = "down"))) } }, enabled = state.interactive) { Text("Scroll down") }
                            }
                        }
                        if (keyboard) KeyTray(state, actions)
                        if (screen.capabilities.tabs) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { actions.open("about:blank") }, enabled = state.connected && !state.pending, style = ButtonStyle.Ghost) { Text("New tab") }
                            Button(onClick = { actions.command(ScreenCommandKind.Close, null) }, enabled = state.interactive, style = ButtonStyle.Ghost) { Text("Close tab") }
                        }
                    }
                    if (!wide) Button(onClick = { overview = true }, style = ButtonStyle.Outlined, modifier = Modifier.fillMaxWidth()) { Text("Screens · ${state.screens.size}") }
                }
            }
        }
        if (!wide) BottomSheet(overview, onDismissRequest = { overview = false }, header = { Text("Screens", Modifier.padding(16.dp)) }) {
            ScreenOverview(state, LiveActions(select = { actions.select(it); overview = false }, drawn = actions.drawn, overview = actions.overview), Modifier.weight(1f))
        }
    }
}

@Composable
private fun ControlBar(state: LiveViewState, actions: LiveActions) {
    val control = state.control
    Column(Modifier.fillMaxWidth().background(color(panelColor)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when {
            !state.connected -> "Disconnected · Takeover stays paused"
            control.draining || state.pending -> "Waiting for the last step…"
            control.suspended -> "Takeover suspended · Agents are waiting"
            state.ownsControl -> "You're in control"
            control.holder != null -> "Controlled from another Device"
            state.screen?.active == true -> "Agent is working"
            else -> "Watching"
        }, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.ownsControl) Button(onClick = { actions.takeover(true, control.holder != null) }, enabled = state.connected && !state.pending && !control.draining) {
                Text(if (control.holder != null) "Take over here" else "Take over")
            }
            if (control.holder != null) Button(onClick = { actions.takeover(false, false) }, enabled = state.connected && !state.pending, style = ButtonStyle.Outlined) { Text("Hand back") }
        }
    }
}

@Composable
internal fun ScreenOverview(state: LiveViewState, actions: LiveActions, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val visible by rememberUpdatedState(actions.overview)
    LaunchedEffect(list, state.screens.map { it.ref }) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { state.screens.getOrNull(it.index - 1)?.ref } }
            .distinctUntilChanged().collect { visible(it) }
    }
    DisposableEffect(Unit) { onDispose { visible(emptyList()) } }
    LazyColumn(modifier.padding(12.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Screens") }
        items(state.screens, key = { "${it.ref.source}/${it.ref.owner}/${it.ref.id}" }) { screen ->
            Column(Modifier.fillMaxWidth().background(color(panelColor)).clickable { actions.select(screen.ref) }.padding(12.dp)) {
                state.thumbnails[screen.ref]?.let { frame ->
                    LiveCanvas(frame, false, { _, _ -> }, actions.drawn, {}, Modifier.fillMaxWidth().aspectRatio(1.6f))
                }
                Text(screen.title, maxLines = 2)
                Text(when { screen.control.suspended -> "Takeover suspended"; screen.control.holder != null -> "User control"; else -> screen.status.name }, color = color(mutedColor))
            }
        }
    }
}

private data class DecodedFrame(val frame: ScreenFrame, val bitmap: ImageBitmap)

@Composable
internal fun LiveCanvas(frame: ScreenFrame?, interactive: Boolean, input: (ScreenFrame, List<ScreenAction>) -> Unit,
    drawn: (ScreenFrame) -> Unit, failed: () -> Unit, modifier: Modifier = Modifier) {
    var decoded by remember(frame?.subscription, frame?.epoch) { mutableStateOf<DecodedFrame?>(null) }
    LaunchedEffect(frame) {
        frame ?: return@LaunchedEffect
        if (frame.sample.width <= 0 || frame.sample.height <= 0) { failed(); return@LaunchedEffect }
        val bitmap = withContext(Dispatchers.Default) { runCatching { attachmentImage(Base64.decode(frame.sample.image), fullSize = true) }.getOrNull() }
        if (bitmap == null) failed() else decoded = DecodedFrame(frame, bitmap)
    }
    var zoom by remember(frame?.subscription) { mutableStateOf(1f) }
    var pan by remember(frame?.subscription) { mutableStateOf(Offset.Zero) }
    var painted by remember(frame?.subscription) { mutableStateOf<ScreenFrame?>(null) }
    LaunchedEffect(painted) { painted?.let(drawn) }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val shown = decoded
        if (shown == null) Text("Waiting for a fresh screen…", color = color(mutedColor))
        else Image(shown.bitmap, "Live screen", Modifier.aspectRatio(shown.bitmap.width.toFloat() / shown.bitmap.height)
            .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
            .drawWithContent { drawContent(); if (painted != shown.frame) painted = shown.frame }
            .pointerInput(shown, interactive) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var transformed = false
                    var last = down.position
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            transformed = true
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                            pan = if (zoom == 1f) Offset.Zero else pan + event.calculatePan()
                        }
                        last = event.changes.first().position
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (interactive && !transformed) {
                        val x = (last.x / size.width * shown.frame.sample.width).roundToInt().coerceIn(0, shown.frame.sample.width - 1)
                        val y = (last.y / size.height * shown.frame.sample.height).roundToInt().coerceIn(0, shown.frame.sample.height - 1)
                        val action = if ((last - down.position).getDistance() < 12.dp.toPx()) ScreenAction(ScreenActionKind.Tap, x = x, y = y)
                        else ScreenAction(ScreenActionKind.Swipe,
                            x = (down.position.x / size.width * shown.frame.sample.width).roundToInt().coerceIn(0, shown.frame.sample.width - 1),
                            y = (down.position.y / size.height * shown.frame.sample.height).roundToInt().coerceIn(0, shown.frame.sample.height - 1), toX = x, toY = y)
                        input(shown.frame, listOf(action))
                    }
                }
            }, contentScale = ContentScale.Fit)
        if (zoom > 1f) Button(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.align(Alignment.TopEnd)) { Text("Fit") }
    }
}

@Composable
private fun KeyTray(state: LiveViewState, actions: LiveActions) {
    val text = rememberTextFieldState()
    val key = rememberTextFieldState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if ("type" in state.screen!!.capabilities.inputs) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Input(text, "Type into focused field") }
            Button(onClick = { state.displayed?.let { actions.input(it, listOf(ScreenAction(ScreenActionKind.Type, text = text.text.toString()))) } }, enabled = state.interactive) { Text("Type") }
        }
        if ("key" in state.screen!!.capabilities.inputs) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Input(key, "Key or shortcut") }
            Button(onClick = { state.displayed?.let { actions.input(it, listOf(ScreenAction(ScreenActionKind.Key, key = key.text.toString()))) } }, enabled = state.interactive) { Text("Press") }
        }
    }
}
