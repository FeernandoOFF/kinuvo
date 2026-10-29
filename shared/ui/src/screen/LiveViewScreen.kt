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
fun LiveViewScreen(model: LiveViewModel, back: (() -> Unit)?, docked: Boolean = false, extra: List<PageAction> = emptyList()) {
    val state by model.state.collectAsState()
    DisposableEffect(model) { model.visible(true); onDispose { model.visible(false) } }
    LiveViewContent(state, LiveActions(model::select, model::takeover, model::open, model::command,
        model::input, model::drawn, model::decodeFailed, model::retry, model::showOverview, model::close), back, docked, extra)
}

internal class LiveActions(
    val select: (ScreenRef) -> Unit = {}, val takeover: (Boolean, Boolean) -> Unit = { _, _ -> },
    val open: (String) -> Unit = {}, val command: (ScreenCommandKind, String?) -> Unit = { _, _ -> },
    val input: (ScreenFrame, List<ScreenAction>) -> Unit = { _, _ -> }, val drawn: (ScreenFrame) -> Unit = {},
    val decodeFailed: () -> Unit = {}, val retry: () -> Unit = {}, val overview: (List<ScreenRef>) -> Unit = {},
    val close: (ScreenRef) -> Unit = {},
)

/**
 * One layout for every Screen: a Browser's tabs and address above, a Computer list beside on wide windows, the Screen
 * framed by who controls it, and takeover below. [docked] trims the header for the panel beside a chat.
 */
@Composable
internal fun LiveViewContent(state: LiveViewState, actions: LiveActions, back: (() -> Unit)?, docked: Boolean = false, extra: List<PageAction> = emptyList()) {
    var keyboard by remember { mutableStateOf(false) }
    val tabs = state.owner != null

    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor)).then(if (docked) Modifier else Modifier.safeDrawingPadding()).imePadding()) {
        val wide = maxWidth >= 760.dp
        var overview by rememberSaveable(wide, tabs) { mutableStateOf(false) }
        val headerActions = buildList {
            if (state.error != null || !state.connected) add(PageAction(Lucide.RefreshCw, "Reconnect", actions.retry))
            if (tabs) add(PageAction(Lucide.LayoutGrid, "Screens", { overview = !overview }))
            addAll(extra)
        }
        Column(Modifier.fillMaxSize()) {
            if (back == null && !docked) PageHeader(if (tabs) Lucide.Globe else Lucide.Monitor, if (tabs) "Browser" else "Computers",
                subtitle = state.screen?.title, actions = headerActions)
            else {
                TitleBarRow(leading = !docked) {
                    back?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
                    Icon(if (tabs) Lucide.Globe else Lucide.Monitor, Modifier.padding(start = 6.dp).size(16.dp), tint = color(mutedColor))
                    Text(if (tabs) "Browser" else state.screen?.title ?: "Computers", Modifier.padding(start = 4.dp), singleLine = true)
                    Spacer(Modifier.weight(1f))
                    headerActions.forEach { HeaderButton(it.icon, it.label, it.onClick) }
                }
                HorizontalSeparator()
            }
            if (tabs) TabStrip(state, actions)
            state.screen?.takeIf { it.capabilities.navigation }?.let { AddressBar(state, actions) }
            Banner(state)
            Row(Modifier.weight(1f)) {
                if (wide && overview && tabs) {
                    ScreenOverview(state, actions, Modifier.width(240.dp).fillMaxHeight())
                    VerticalSeparator()
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val screen = state.screen
                    if (screen == null) EmptyStage(state, actions, Modifier.weight(1f).fillMaxWidth())
                    else {
                        Stage(state, screen, actions, Modifier.weight(1f).fillMaxWidth().padding(if (wide) 12.dp else 8.dp))
                        if (keyboard && state.ownsControl) Box(Modifier.padding(horizontal = 12.dp)) { KeyTray(state, actions) }
                        ControlBar(state, actions, keyboard, { keyboard = !keyboard }, Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
        if (!wide && tabs) BottomSheet(overview, onDismissRequest = { overview = false }, header = { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Screens · ${state.screens.size}", Modifier.weight(1f))
            if (tabs) Button(onClick = { actions.open("about:blank") }, enabled = state.connected, style = ButtonStyle.Ghost) { Text("New tab") }
        } }) {
            ScreenOverview(state, LiveActions(select = { actions.select(it); overview = false }, open = actions.open, command = actions.command, drawn = actions.drawn, overview = actions.overview, close = actions.close), Modifier.weight(1f), grid = true)
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
private fun TabStrip(state: LiveViewState, actions: LiveActions) {
    Row(Modifier.fillMaxWidth().background(color(sidebarColor)).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        state.screens.forEach { screen ->
            val selected = screen.ref == state.selected
            Row(Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) color(panelColor) else Color.Transparent)
                .then(if (selected) Modifier.border(1.dp, color(borderColor), RoundedCornerShape(8.dp)) else Modifier)
                .clickable(onClickLabel = screen.title) { actions.select(screen.ref) }.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (screen.active) Spinner(size = 12.dp) else Icon(if (screen.ref.owner == null) Lucide.Monitor else Lucide.Globe, Modifier.size(13.dp), tint = color(mutedColor))
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
private fun AddressBar(state: LiveViewState, actions: LiveActions) {
    var address by remember(state.selected, state.screen?.currentUrl) { mutableStateOf(state.screen?.currentUrl.orEmpty()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun Banner(state: LiveViewState) {
    val (text, tone) = state.error?.let { it to Tone.Danger } ?: when {
        !state.connected -> "Disconnected · Takeover stays paused until explicit handback" to Tone.Neutral
        state.requestReason != null -> "The Agent needs you: ${state.requestReason}" to Tone.Warning
        else -> return
    }
    Pill(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), tone, if (tone == Tone.Warning) Lucide.Hand else null)
}

@Composable
private fun EmptyStage(state: LiveViewState, actions: LiveActions, modifier: Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.pending || !state.loaded) Spinner(size = 20.dp) else Icon(if (state.owner != null) Lucide.Globe else Lucide.Monitor, Modifier.size(28.dp), tint = color(mutedColor))
        Text(if (state.pending) "Preparing Browser…" else if (state.loaded) "No screens yet. Ask the Agent to browse or connect a Computer." else "Finding screens…",
            color = color(mutedColor), textAlign = TextAlign.Center)
        if (state.owner != null) Button(onClick = { actions.open("about:blank") }, enabled = state.connected && !state.pending) { Text("Open Browser") }
    }
}

@Composable
private fun Stage(state: LiveViewState, screen: ScreenInfo, actions: LiveActions, modifier: Modifier) {
    val status = state.status ?: Status.Watching
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(color(sidebarColor))
        .border(if (status == Status.Yours) 2.dp else 1.dp, if (status == Status.Watching || status == Status.Offline) color(borderColor) else status.tone.color(), shape)) {
        LiveCanvas(state.frame, state.interactive, actions.input, actions.drawn, actions.decodeFailed, Modifier.fillMaxSize(), state.control.generation, "scroll" in screen.capabilities.inputs)
        if (!state.connected) state.frameReceivedAt?.let { Text("Last frame · $it", Modifier.align(Alignment.BottomStart).padding(8.dp), fontSize = 12.sp, color = color(mutedColor)) }
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
                .clickable { actions.select(screen.ref) }.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveCanvas(if (selected) state.frame else state.thumbnails[screen.ref], false,
                    { _, _ -> }, actions.drawn, {}, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(6.dp)).background(color(backgroundColor)))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (screen.ref.owner == null) Lucide.Monitor else Lucide.Globe, Modifier.size(13.dp), tint = color(mutedColor))
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
    val binding = CanvasBinding(frame?.screen, frame?.subscription, frame?.epoch, generation, interactive)
    val latestBinding by rememberUpdatedState(binding)
    val blank = remember { ImageBitmap(1, 1) }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.TopCenter) {
        val shown = gesture ?: decoded
        val latest by rememberUpdatedState(shown)
        if (shown == null) Text("Waiting for a fresh screen…", color = color(mutedColor))
        Image(shown?.bitmap ?: blank, if (shown == null) null else "Live screen", Modifier.aspectRatio(shown?.bitmap?.let { it.width.toFloat() / it.height } ?: 1f)
            .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
            .drawWithContent { drawContent(); if (shown != null && painted != shown.frame) painted = shown.frame }
            .pointerInput(binding) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val shown = latest ?: return@awaitEachGesture
                    val started = latestBinding
                    if (shown.frame.screen != started.screen || shown.frame.subscription != started.subscription || shown.frame.epoch != started.epoch) return@awaitEachGesture
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
                        if (started.interactive && started == latestBinding && !transformed) {
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

/** The Computers Agents can control: a list, opening one to its Live view, beside the list on wide windows. */
@Composable
fun ComputersScreen(model: LiveViewModel, list: Boolean, onOpen: (ScreenRef) -> Unit, back: (() -> Unit)?) {
    val state by model.state.collectAsState()
    val actions = LiveActions(model::select, model::takeover, model::open, model::command, model::input, model::drawn, model::decodeFailed, model::retry, model::showOverview, model::close)
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
                        GroupRow(screen.title, { onOpen(screen.ref) }, status.label, Lucide.Monitor, selected = screen.ref == selected) {
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
