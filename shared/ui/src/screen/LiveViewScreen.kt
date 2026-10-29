package kinuvo.ui.screen

import kinuvo.ui.navigation.PageHeader
import kinuvo.ui.navigation.PageAction

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    var editingAddress by remember(state.selected) { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor)).safeDrawingPadding().imePadding()) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize()) {
            if (back == null) PageHeader(Lucide.MonitorSmartphone, state.screen?.title ?: "Screens",
                subtitle = state.screen?.currentUrl, actions = buildList {
                    if (state.error != null || !state.connected) add(PageAction(Lucide.RefreshCw, "Reconnect", actions.retry))
                    add(PageAction(Lucide.LayoutGrid, "Screens", { overview = !overview }))
                }) else TitleBarRow(trailing = false) {
                back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
                Column(Modifier.weight(1f)) {
                    Text(state.screen?.title ?: "Screens", maxLines = 1)
                    Text(listOfNotNull(state.screen?.currentUrl?.substringAfter("://")?.substringBefore("/"), state.selected?.owner?.let { "Agent ${it.agentId}" }).joinToString(" · "), maxLines = 1, color = color(mutedColor), modifier = Modifier.clickable(enabled = state.interactive && state.screen?.capabilities?.navigation == true) { editingAddress = !editingAddress })
                }
                if (state.error != null || !state.connected) HeaderButton(Lucide.RefreshCw, "Reconnect", actions.retry)
                HeaderButton(Lucide.LayoutGrid, "Screens", { overview = !overview })
            }
            if (!wide && editingAddress && state.interactive) Address(state, actions)
            val banner = state.error ?: if (!state.connected) "Disconnected · Takeover stays paused until explicit handback"
                else state.requestReason?.let { "The Agent needs you: $it" }
            banner?.let { Text(it, Modifier.padding(12.dp)) }
            Row(Modifier.weight(1f)) {
                if (wide && overview) ScreenOverview(state, actions, Modifier.width(220.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().padding(if (wide) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val screen = state.screen
                    if (screen == null) {
                        Text(if (state.pending) "Preparing Browser…" else if (state.loaded) "No screens yet. Ask the Agent to browse or connect a Computer." else "Finding screens…", Modifier.padding(16.dp))
                        if (state.owner != null) Button(onClick = { actions.open("about:blank") }, enabled = state.connected && !state.pending) { Text("Open Browser") }
                    } else {
                        if (wide && screen.capabilities.navigation) Address(state, actions)
                        Box(Modifier.weight(1f).fillMaxWidth().border(2.dp, color(when {
                            !state.connected || state.control.suspended -> mutedColor
                            state.ownsControl -> successColor
                            state.requestReason != null -> warningColor
                            else -> accentColor
                        }))) {
                            LiveCanvas(state.frame, state.interactive, actions.input, actions.drawn, actions.decodeFailed, Modifier.fillMaxSize(), state.control.generation, "scroll" in screen.capabilities.inputs)
                            if (!state.connected) state.frameReceivedAt?.let { Text("Last frame · $it", Modifier.align(Alignment.BottomStart).background(color(panelColor)).padding(6.dp), color = color(mutedColor)) }
                            Text(when { state.control.suspended -> "Takeover suspended"; state.ownsControl -> "You’re in control"; state.control.holder != null -> "Another Device"; screen.active -> "Agent is working"; else -> "Watching" }, Modifier.align(Alignment.TopStart).background(color(panelColor)).padding(6.dp), color = color(mutedColor))
                        }
                        if (keyboard && state.ownsControl) KeyTray(state, actions)
                        Row(Modifier.widthIn(max = if (wide) 480.dp else 10000.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.ownsControl) HeaderButton(Lucide.Keyboard, "Keyboard", { keyboard = !keyboard })
                            Box(Modifier.weight(1f)) { ControlBar(state, actions) }
                            HeaderButton(Lucide.LayoutGrid, "Screens · ${state.screens.size}", { overview = !overview })
                        }
                    }

                }
            }
        }
        if (!wide) BottomSheet(overview, onDismissRequest = { overview = false }, header = { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Screens · ${state.screens.size}", Modifier.weight(1f))
            Button(onClick = { actions.open("about:blank") }, enabled = state.connected && state.owner != null, style = ButtonStyle.Ghost) { Text("New tab") }
        } }) {
            ScreenOverview(state, LiveActions(select = { actions.select(it); overview = false }, open = actions.open, command = actions.command, drawn = actions.drawn, overview = actions.overview), Modifier.weight(1f), grid = true)
        }
    }
}

@Composable
private fun ControlBar(state: LiveViewState, actions: LiveActions) {
    val control = state.control
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.takeover(!state.ownsControl, control.holder != null) },
            enabled = state.connected && !state.pending && !control.draining,
            modifier = Modifier.weight(1f)) {
            Text(when { state.pending || control.draining -> "Waiting…"; state.ownsControl -> "Hand back to Agent"; control.holder != null -> "Take over here"; else -> "Take over" })
        }
        if (control.holder != null && !state.ownsControl) Button(onClick = { actions.takeover(false, false) },
            enabled = state.connected && !state.pending && !control.draining, style = ButtonStyle.Ghost) { Text("Hand back") }
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
    LazyVerticalGrid(GridCells.Fixed(if (grid) 2 else 1), modifier.background(color(panelColor)).padding(12.dp), state = list,
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.screens, key = { "${it.ref.source}/${it.ref.owner}/${it.ref.id}" }) { screen ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .border(1.dp, color(if (screen.ref == state.selected) accentColor else borderColor), RoundedCornerShape(12.dp))
                .clickable { actions.select(screen.ref) }.padding(8.dp)) {
                LiveCanvas(if (screen.ref == state.selected) state.frame else state.thumbnails[screen.ref], false,
                    { _, _ -> }, actions.drawn, {}, Modifier.fillMaxWidth().aspectRatio(1.6f))
                Text(screen.title, maxLines = 2)
                Text(when { screen.control.suspended -> "Takeover suspended"; screen.control.holder != null -> "User control"; screen.active -> "Agent working"; screen.status == ScreenStatus.Ready -> "Watching"; else -> "Unavailable" }, color = color(mutedColor))
                if (screen.ref == state.selected && screen.capabilities.tabs && state.interactive)
                    Button(onClick = { actions.command(ScreenCommandKind.Close, null) }, style = ButtonStyle.Ghost) { Text("Close tab") }
            }
        }
        if (!grid) item { Button(onClick = { actions.open("about:blank") }, enabled = state.connected && state.owner != null) { Text("New tab") } }
    }
}

private data class DecodedFrame(val frame: ScreenFrame, val bitmap: ImageBitmap)

@Composable
internal fun LiveCanvas(frame: ScreenFrame?, interactive: Boolean, input: (ScreenFrame, List<ScreenAction>) -> Unit,
    drawn: (ScreenFrame) -> Unit, failed: () -> Unit, modifier: Modifier = Modifier, generation: Long = 0, scroll: Boolean = false) {
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
    Box(modifier.clipToBounds(), contentAlignment = Alignment.TopCenter) {
        val shown = gesture ?: decoded
        val latest by rememberUpdatedState(shown)
        if (shown == null) Text("Waiting for a fresh screen…", color = color(mutedColor))
        else Image(shown.bitmap, "Live screen", Modifier.aspectRatio(shown.bitmap.width.toFloat() / shown.bitmap.height)
            .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
            .drawWithContent { drawContent(); if (painted != shown.frame) painted = shown.frame }
            .pointerInput(frame?.screen, frame?.subscription, frame?.epoch, generation, interactive) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val shown = latest ?: return@awaitEachGesture
                    gesture = shown
                    val bounds = size
                    try {
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
                            val x = (last.x / bounds.width * shown.frame.sample.width).roundToInt().coerceIn(0, shown.frame.sample.width - 1)
                            val y = (last.y / bounds.height * shown.frame.sample.height).roundToInt().coerceIn(0, shown.frame.sample.height - 1)
                            val action = if ((last - down.position).getDistance() < 12.dp.toPx()) ScreenAction(ScreenActionKind.Tap, x = x, y = y)
                            else if (scroll) ScreenAction(ScreenActionKind.Scroll, direction = if (last.y > down.position.y) "up" else "down")
                            else ScreenAction(ScreenActionKind.Swipe,
                                x = (down.position.x / bounds.width * shown.frame.sample.width).roundToInt().coerceIn(0, shown.frame.sample.width - 1),
                                y = (down.position.y / bounds.height * shown.frame.sample.height).roundToInt().coerceIn(0, shown.frame.sample.height - 1), toX = x, toY = y)
                            input(shown.frame, listOf(action))
                        }
                    } finally { gesture = null }
                }
            }, contentScale = ContentScale.Fit)
        if (zoom > 1f) Button(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.align(Alignment.TopEnd)) { Text("${(zoom * 100).roundToInt()}% · Fit") }
    }
}

@Composable
private fun KeyTray(state: LiveViewState, actions: LiveActions) {
    val text = rememberTextFieldState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if ("type" in state.screen!!.capabilities.inputs) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val send = { state.displayed?.let { actions.input(it, listOf(ScreenAction(ScreenActionKind.Type, text = text.text.toString()))) }; Unit }
            androidx.compose.foundation.text.BasicTextField(text.text.toString(), { value -> text.edit { replace(0, length, value) } },
                Modifier.weight(1f).background(color(panelColor)).padding(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = color(mutedColor)),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { send() }))
            Button(onClick = send, enabled = state.interactive) { Text("Send") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.screen!!.capabilities.keys.forEach { key ->
                Button(onClick = { state.displayed?.let { actions.input(it, listOf(ScreenAction(ScreenActionKind.Key, key = key))) } }, enabled = state.interactive, style = ButtonStyle.Outlined) { Text(key) }
            }
        }
    }
}

@Composable
private fun Address(state: LiveViewState, actions: LiveActions) {
    var address by remember(state.selected, state.screen?.currentUrl) { mutableStateOf(state.screen?.currentUrl.orEmpty()) }
    androidx.compose.foundation.text.BasicTextField(address, { address = it },
        Modifier.fillMaxWidth().background(color(panelColor)).padding(10.dp),
        enabled = state.interactive, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = color(mutedColor), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { actions.command(ScreenCommandKind.Navigate, address) }))
}
