package kinuvo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.screen.LiveViewState
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DockShape = RoundedCornerShape(24.dp)
private val StageShape = RoundedCornerShape(20.dp)

/** Phone layout: a themed stage framed by who controls it, with a floating dock for keyboard, keys, pointer, clipboard and screens. */
@Composable
internal fun MobileLiveViewContent(state: LiveViewState, actions: LiveActions, back: (() -> Unit)?) {
    var restartTo by remember(state.owner) { mutableStateOf<Boolean?>(null) }
    var controls by remember { mutableStateOf(false) }
    var keyTray by remember(state.selected, state.control.generation) { mutableStateOf(false) }
    var keyboard by remember(state.selected, state.control.generation) { mutableStateOf(false) }
    var trackpad by remember(state.selected, state.control.generation) { mutableStateOf(false) }
    var recenter by remember(state.selected) { mutableIntStateOf(0) }
    var fitPhone by remember(state.selected) { mutableStateOf(false) }
    var pasteError by remember(state.selected) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val clipboard = rememberScreenClipboard()
    val latest by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val screen = state.screen
    val (status, tone) = when {
        !state.connected -> "Disconnected" to Tone.Neutral
        state.control.suspended -> "Takeover suspended" to Tone.Warning
        state.ownsControl -> "In control" to Tone.Success
        state.control.holder != null -> "Controlled elsewhere" to Tone.Warning
        screen?.active == true -> "Agent is working" to Tone.Accent
        else -> "Watching" to Tone.Neutral
    }
    val paste: () -> Unit = {
        val before = state
        pasteError = null
        // Clipboard access happens only in this explicit user action.
        scope.launch {
            try {
                val text = clipboard().orEmpty()
                val current = latest
                if (text.isNotEmpty() && current.interactive && current.selected == before.selected &&
                    current.subscription == before.subscription && current.control.generation == before.control.generation) {
                    current.displayed?.let { actions.input(it, listOf(ScreenAction(ScreenActionKind.Type, text = text))) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pasteError = "Could not read the clipboard. Use the keyboard’s Paste action." }
        }
        Unit
    }
    val sendKey = { key: String -> state.displayed?.takeIf { state.interactive }?.let { actions.input(it, listOf(ScreenAction(ScreenActionKind.Key, key = key))) } }
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            back?.let { RoundButton(Lucide.ArrowLeft, "Back", it) }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color(accentColor).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(if (state.owner != null) Lucide.Globe else Lucide.Monitor, Modifier.size(18.dp), tint = color(accentColor))
            }
            Column(Modifier.weight(1f)) {
                Text(screen?.title?.ifBlank { null } ?: if (state.owner != null) "Browser" else "Computer", singleLine = true, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (tone == Tone.Neutral) color(mutedColor) else tone.color()))
                    Text(status, fontSize = 12.sp, color = if (tone == Tone.Neutral) color(mutedColor) else tone.color(), singleLine = true)
                }
            }
            if (state.ownsControl) Row(Modifier.clip(CircleShape).background(color(subtleColor))
                .clickable(enabled = state.connected && !state.pending && !state.control.draining, onClickLabel = "Hand back to Agent") { actions.takeover(false, false) }
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Lucide.Hand, Modifier.size(15.dp), tint = Tone.Success.color())
                Text("Hand back", fontSize = 13.sp)
            }
            ScreenOptions(state, trackpad, fitPhone, { recenter++ }, { fitPhone = !fitPhone }, { restartTo = it }, actions)
        }
        Banner(state)
        pasteError?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Tone.Warning.color(), fontSize = 12.sp) }
        if (screen == null) EmptyStage(state, actions, Modifier.weight(1f).fillMaxWidth())
        else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                val w = if (fitPhone) maxWidth.value.roundToInt() else 1280
                val h = if (fitPhone) maxHeight.value.roundToInt() else 800
                LaunchedEffect(screen.ref, state.control.generation, state.ownsControl, state.pending, w, h) {
                    if (state.ownsControl && screen.capabilities.resize && !state.pending) { delay(180); actions.resize(w, h) }
                }
                val frame = when (tone) { Tone.Success, Tone.Warning, Tone.Accent -> tone.color().copy(alpha = 0.6f); else -> color(borderColor) }
                Box(Modifier.fillMaxSize().clip(StageShape).background(color(sidebarColor)).border(if (state.ownsControl) 2.dp else 1.dp, frame, StageShape)) {
                    LiveCanvas(state.frame, state.interactive, actions.input, actions.drawn, actions.decodeFailed, Modifier.fillMaxSize(),
                        state.control.generation, "scroll" in screen.capabilities.inputs, trackpad = trackpad, recenter = recenter)
                }
            }
            if (state.ownsControl && "type" in screen.capabilities.inputs) key(state.selected, state.control.generation) {
                RemoteKeyboard(state, actions, focus, keyboard, compact = true)
            }
            Column(Modifier.fillMaxWidth().padding(12.dp).shadow(12.dp, DockShape).clip(DockShape).background(color(panelColor))
                .border(1.dp, color(borderColor), DockShape).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val capabilities = screen.capabilities
                val screensLabel = if (state.owner != null) "Tabs & address" else "Computers"
                val screensIcon = if (state.owner != null) Lucide.PanelsTopLeft else Lucide.MonitorSmartphone
                if (!state.ownsControl) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DockButton(screensIcon, screensLabel, { controls = true })
                    Text(if (state.control.holder != null) "Another Device has control" else "Take over to tap and type",
                        Modifier.weight(1f), fontSize = 13.sp, color = color(mutedColor), maxLines = 2)
                    Button(onClick = { actions.takeover(true, state.control.holder != null) }, enabled = state.connected && !state.pending && !state.control.draining, style = ButtonStyle.Primary) {
                        Icon(Lucide.Hand, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.pending || state.control.draining) "Waiting…" else if (state.control.holder != null) "Take over here" else "Take over")
                    }
                } else {
                    if (keyTray) key(state.selected, state.control.generation) { MobileKeyTray(state, actions) }
                    val android = "Recents" in capabilities.keys
                    val desktop = state.owner == null && !android
                    val copyModifier = capabilities.modifiers.firstOrNull { it == "Command" } ?: capabilities.modifiers.firstOrNull { it == "Control" }
                    val switcher = when {
                        android -> "Recents"
                        desktop && "Tab" in capabilities.keys && "Command" in capabilities.modifiers -> "Command+Tab"
                        desktop && "Tab" in capabilities.keys && "Alt" in capabilities.modifiers -> "Alt+Tab"
                        else -> null
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        DockButton(if (keyboard) Lucide.KeyboardOff else Lucide.Keyboard, if (keyboard) "Hide keyboard" else "Keyboard", { keyboard = !keyboard },
                            enabled = state.connected && "type" in capabilities.inputs, selected = keyboard)
                        DockButton(if (android) Lucide.Smartphone else Lucide.Command, if (keyTray) "Hide keys" else "Keys", { keyTray = !keyTray },
                            enabled = capabilities.keys.isNotEmpty(), selected = keyTray)
                        DockButton(Lucide.MousePointer2, "Trackpad mode", { trackpad = !trackpad }, enabled = "tap" in capabilities.inputs, selected = trackpad)
                        ClipboardButton(state, paste, desktop && "C" in capabilities.keys && copyModifier != null,
                            { sendKey("$copyModifier+C") }, { sendKey("$copyModifier+V") }, "V" in capabilities.keys)
                        switcher?.let { DockButton(Lucide.ArrowRightLeft, "Switch apps", { sendKey(it) }, enabled = state.interactive) }
                        DockButton(screensIcon, screensLabel, { controls = true })
                    }
                }
            }
        }
    }
    BrowserRestartDialog(restartTo, state, actions) { restartTo = null }
    BottomSheet(controls, { controls = false }, header = {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.owner != null) "Browser" else "Computers", Modifier.weight(1f))
            if (state.owner != null) Button(onClick = { actions.open("about:blank") }, enabled = state.connected && !state.pending, style = ButtonStyle.Ghost) { Text("New tab") }
            RoundButton(Lucide.X, "Close screen controls", { controls = false })
        }
    }) {
        if (state.owner != null) {
            TabStrip(state, actions.copy(select = { actions.select(it); controls = false }))
            screen?.takeIf { it.capabilities.navigation }?.let { AddressBar(state, actions) }
        }
        ScreenOverview(state, actions.copy(select = { actions.select(it); controls = false }), Modifier.weight(1f).fillMaxWidth(), grid = true)
    }
}

@Composable
private fun ScreenOptions(state: LiveViewState, trackpad: Boolean, fitPhone: Boolean, recenter: () -> Unit, fit: () -> Unit, restart: (Boolean) -> Unit, actions: LiveActions) {
    var menu by remember { mutableStateOf(false) }
    val screen = state.screen
    DropdownMenu(expanded = menu, onExpandedChange = { menu = it }, panel = {
        DropdownMenuPanel {
            BrowserPresentationMenuItems(state) { menu = false; restart(it) }
            if (trackpad) DropdownMenuItem(onClick = { menu = false; recenter() }, enabled = state.interactive) {
                MobileMenuItem(Lucide.Crosshair, "Recenter pointer")
            }
            if (screen?.capabilities?.resize == true) DropdownMenuItem(onClick = { menu = false; fit() }, enabled = state.ownsControl) {
                MobileMenuItem(if (fitPhone) Lucide.Check else Lucide.Smartphone, "Fit to phone")
            }
            DropdownMenuItem(onClick = { menu = false; actions.takeover(!state.ownsControl, state.control.holder != null) },
                enabled = state.connected && !state.pending && !state.control.draining && screen != null) {
                MobileMenuItem(Lucide.Hand, if (state.ownsControl) "Hand back to Agent" else if (state.control.holder != null) "Take over here" else "Take over")
            }
            if (state.error != null || !state.connected) DropdownMenuItem(onClick = { menu = false; actions.retry() }) {
                MobileMenuItem(Lucide.RefreshCw, "Reconnect")
            }
        }
    }) { RoundButton(Lucide.Ellipsis, "Screen options", { menu = true }) }
}

/** Paste always sends this device's clipboard as typed text; desktop Computers also get their own copy and paste shortcuts. */
@Composable
private fun ClipboardButton(state: LiveViewState, paste: () -> Unit, shortcuts: Boolean, copy: () -> Unit, pasteRemote: () -> Unit, remotePaste: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val typing = state.interactive && state.screen?.capabilities?.inputs?.contains("type") == true
    DropdownMenu(expanded = menu, onExpandedChange = { menu = it }, panel = {
        DropdownMenuPanel {
            DropdownMenuItem(onClick = { menu = false; paste() }, enabled = typing) { MobileMenuItem(Lucide.ClipboardPaste, "Paste") }
            if (shortcuts) {
                DropdownMenuItem(onClick = { menu = false; copy() }, enabled = state.interactive) { MobileMenuItem(Lucide.Copy, "Copy on screen") }
                if (remotePaste) DropdownMenuItem(onClick = { menu = false; pasteRemote() }, enabled = state.interactive) { MobileMenuItem(Lucide.Clipboard, "Paste on screen") }
            }
        }
    }) { DockButton(Lucide.ClipboardPaste, "Clipboard", { menu = true }, enabled = typing || shortcuts && state.interactive) }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, style = ButtonStyle.Ghost, shape = CircleShape,
        modifier = Modifier.size(40.dp).background(color(subtleColor), CircleShape)) {
        Icon(icon, Modifier.size(19.dp), contentDescription = label, tint = if (enabled) color(onBackgroundColor) else color(mutedColor))
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, selected: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    IconButton(onClick = onClick, enabled = enabled, style = ButtonStyle.Ghost, shape = shape,
        modifier = Modifier.size(46.dp).background(if (selected) color(accentColor).copy(alpha = 0.16f) else Color.Transparent, shape)
            .semantics { stateDescription = if (selected) "On" else "Off" }) {
        Icon(icon, Modifier.size(22.dp), contentDescription = label, tint = when {
            !enabled -> color(mutedColor).copy(alpha = 0.5f)
            selected -> color(accentColor)
            else -> color(onPanelColor)
        })
    }
}

@Composable
private fun MobileMenuItem(icon: ImageVector, label: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, Modifier.size(18.dp))
        Text(label)
    }
}

private val KeyIcons = mapOf("ArrowLeft" to Lucide.ArrowLeft, "Left" to Lucide.ArrowLeft, "ArrowUp" to Lucide.ArrowUp, "Up" to Lucide.ArrowUp,
    "ArrowDown" to Lucide.ArrowDown, "Down" to Lucide.ArrowDown, "ArrowRight" to Lucide.ArrowRight, "Right" to Lucide.ArrowRight,
    "Backspace" to Lucide.Delete, "Enter" to Lucide.CornerDownLeft, "Back" to Lucide.ChevronLeft,
    "Recents" to Lucide.Square, "Notifications" to Lucide.Bell, "Quick_settings" to Lucide.SlidersHorizontal,
    "Meta" to Lucide.Command, "Command" to Lucide.Command, "Shift" to Lucide.ArrowBigUp, "Alt" to Lucide.Option)

private val KeyLabels = mapOf("Escape" to "Esc", "Control" to "Ctrl", "PageUp" to "PgUp", "PageDown" to "PgDn")

@Composable
internal fun MobileKeyTray(state: LiveViewState, actions: LiveActions) {
    val capabilities = state.screen?.capabilities ?: return
    val android = "Recents" in capabilities.keys
    var modifiers by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(state.interactive) { if (!state.interactive) modifiers = emptySet() }
    val primary = listOf("Back", "Home", "Recents", "Notifications", "Quick_settings", "Escape", "ArrowLeft", "Left", "ArrowUp", "Up",
        "ArrowDown", "Down", "ArrowRight", "Right", "Tab", "Backspace", "Enter")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val keys = primary.filter { it in capabilities.keys } + if (modifiers.isNotEmpty()) capabilities.keys.filter { it.length == 1 } else emptyList()
        keys.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    KeyCap(key, enabled = state.interactive, icon = if (android && key == "Home") Lucide.Circle else KeyIcons[key]) {
                        state.displayed?.takeIf { state.interactive }?.let { frame ->
                            actions.input(frame, listOf(ScreenAction(ScreenActionKind.Key, key = (modifiers + key).joinToString("+"))))
                            modifiers = emptySet()
                        }
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (capabilities.modifiers.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            capabilities.modifiers.forEach { key ->
                KeyCap(key, enabled = state.interactive, selected = key in modifiers, icon = KeyIcons[key]) { modifiers = if (key in modifiers) modifiers - key else modifiers + key }
            }
            repeat((5 - capabilities.modifiers.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
        if (modifiers.isNotEmpty()) Text("Choose a key to send ${modifiers.joinToString("+")}+…", Modifier.padding(horizontal = 4.dp), fontSize = 11.sp, color = color(mutedColor))
    }
}

@Composable
private fun RowScope.KeyCap(key: String, enabled: Boolean, selected: Boolean? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val tint = when { !enabled -> color(mutedColor).copy(alpha = 0.5f); selected == true -> color(accentColor); else -> color(onPanelColor) }
    Box(Modifier.weight(1f).height(44.dp).clip(shape)
        .background(if (selected == true) color(accentColor).copy(alpha = 0.16f) else color(subtleColor))
        .then(if (selected == true) Modifier.border(1.dp, color(accentColor).copy(alpha = 0.5f), shape) else Modifier)
        .clickable(enabled = enabled, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = key
            role = Role.Button
            if (selected != null) stateDescription = if (selected) "Selected" else "Not selected"
        }, contentAlignment = Alignment.Center) {
        icon?.let { Icon(it, Modifier.size(18.dp), tint = tint) }
            ?: Text(KeyLabels[key] ?: key.replace('_', ' '), fontSize = 14.sp, color = tint, singleLine = true)
    }
}
