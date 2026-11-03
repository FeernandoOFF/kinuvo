package kinuvo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.*
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

@Composable
internal fun MobileLiveViewContent(state: LiveViewState, actions: LiveActions, back: (() -> Unit)?) {
    var restartTo by remember(state.owner) { mutableStateOf<Boolean?>(null) }
    var menu by remember { mutableStateOf(false) }
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
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            back?.let { MobileScreenButton(Lucide.ArrowLeft, "Back", it) }
            Icon(if (state.owner != null) Lucide.Globe else Lucide.Monitor, Modifier.size(18.dp), tint = Color.White)
            Column(Modifier.weight(1f)) {
                Text(screen?.title ?: if (state.owner != null) "Browser" else "Computer", color = Color.White, singleLine = true, overflow = TextOverflow.Ellipsis)
                Text(when {
                    !state.connected -> "Disconnected"
                    state.control.suspended -> "Takeover suspended"
                    state.ownsControl -> if (trackpad) "In control · Trackpad" else "In control · Touch"
                    state.control.holder != null -> "Controlled elsewhere"
                    else -> "Watching"
                }, fontSize = 11.sp, color = if (state.ownsControl) Tone.Success.color() else color(mutedColor))
            }
            MobileScreenButton(Lucide.SlidersHorizontal, if (keyTray) "Hide controls" else "Controls", { keyTray = !keyTray },
                enabled = state.ownsControl && screen?.capabilities?.keys?.isNotEmpty() == true)
            MobileScreenButton(Lucide.Keyboard, if (keyboard) "Hide keyboard" else "Keyboard", { keyboard = !keyboard },
                enabled = state.connected && state.ownsControl && screen?.capabilities?.inputs?.contains("type") == true)
            DropdownMenu(expanded = menu, onExpandedChange = { menu = it }, panel = {
                DropdownMenuPanel {
                    BrowserPresentationMenuItems(state) { menu = false; restartTo = it }
                    DropdownMenuItem(onClick = { menu = false; trackpad = !trackpad },
                        enabled = state.ownsControl && screen?.capabilities?.inputs?.contains("tap") == true) {
                        MobileMenuItem(if (trackpad) Lucide.Check else Lucide.MousePointer2, "Trackpad mode")
                    }
                    DropdownMenuItem(onClick = {
                        menu = false
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
                    }, enabled = state.interactive && screen?.capabilities?.inputs?.contains("type") == true) {
                        MobileMenuItem(Lucide.ClipboardPaste, "Paste")
                    }
                    if (trackpad) DropdownMenuItem(onClick = { menu = false; recenter++ }, enabled = state.interactive) {
                        MobileMenuItem(Lucide.Crosshair, "Recenter pointer")
                    }
                    if (screen?.capabilities?.resize == true) DropdownMenuItem(onClick = { menu = false; fitPhone = !fitPhone }, enabled = state.ownsControl) {
                        MobileMenuItem(if (fitPhone) Lucide.Check else Lucide.Smartphone, "Fit to phone")
                    }
                    DropdownMenuItem(onClick = { menu = false; controls = true }) {
                        MobileMenuItem(if (state.owner != null) Lucide.PanelsTopLeft else Lucide.Monitor, if (state.owner != null) "Tabs & address" else "Computers")
                    }
                    DropdownMenuItem(onClick = { menu = false; actions.takeover(!state.ownsControl, state.control.holder != null) },
                        enabled = state.connected && !state.pending && !state.control.draining && screen != null) {
                        MobileMenuItem(Lucide.Hand, if (state.ownsControl) "Hand back to Agent" else if (state.control.holder != null) "Take over here" else "Take over")
                    }
                    if (state.error != null || !state.connected) DropdownMenuItem(onClick = { menu = false; actions.retry() }) {
                        MobileMenuItem(Lucide.RefreshCw, "Reconnect")
                    }
                }
            }) { MobileScreenButton(Lucide.Ellipsis, "Screen options", { menu = true }) }
        }
        Banner(state)
        pasteError?.let { Text(it, Modifier.padding(12.dp), color = Tone.Warning.color(), fontSize = 12.sp) }
        if (screen == null) EmptyStage(state, actions, Modifier.weight(1f).fillMaxWidth())
        else {
            if (!state.ownsControl && state.connected) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = { actions.takeover(true, state.control.holder != null) }, enabled = !state.pending && !state.control.draining) {
                    Text(if (state.control.holder != null) "Take over here" else "Take over")
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val w = if (fitPhone) maxWidth.value.roundToInt() else 1280
                val h = if (fitPhone) maxHeight.value.roundToInt() else 800
                LaunchedEffect(screen.ref, state.control.generation, state.ownsControl, state.pending, w, h) {
                    if (state.ownsControl && screen.capabilities.resize && !state.pending) { delay(180); actions.resize(w, h) }
                }
                LiveCanvas(state.frame, state.interactive, actions.input, actions.drawn, actions.decodeFailed, Modifier.fillMaxSize(),
                    state.control.generation, "scroll" in screen.capabilities.inputs,
                    trackpad = trackpad, recenter = recenter)
            }
            if (state.ownsControl && keyTray) key(state.selected, state.control.generation) { MobileKeyTray(state, actions) }
            if (state.ownsControl && "type" in screen.capabilities.inputs) key(state.selected, state.control.generation) {
                RemoteKeyboard(state, actions, focus, keyboard, compact = true)
            }
        }
    }
    BrowserRestartDialog(restartTo, state, actions) { restartTo = null }
    BottomSheet(controls, { controls = false }, header = {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.owner != null) "Browser" else "Computers", Modifier.weight(1f))
            MobileScreenButton(Lucide.X, "Close screen controls", { controls = false })
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
private fun MobileScreenButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, style = ButtonStyle.Ghost, shape = CircleShape,
        modifier = Modifier.size(44.dp).background(color(panelColor), CircleShape)) {
        Icon(icon, Modifier.size(20.dp), contentDescription = label, tint = if (enabled) Color.White else color(mutedColor))
    }
}

@Composable
private fun MobileMenuItem(icon: ImageVector, label: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, Modifier.size(18.dp))
        Text(label)
    }
}

@Composable
internal fun MobileKeyTray(state: LiveViewState, actions: LiveActions) {
    val capabilities = state.screen?.capabilities ?: return
    var modifiers by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(state.interactive) { if (!state.interactive) modifiers = emptySet() }
    val labels = mapOf("Escape" to "Esc", "ArrowLeft" to "←", "Left" to "←", "ArrowUp" to "↑", "Up" to "↑",
        "ArrowDown" to "↓", "Down" to "↓", "ArrowRight" to "→", "Right" to "→", "Backspace" to "⌫",
        "Enter" to "↵", "Quick_settings" to "Quick settings")
    val primary = listOf("Back", "Home", "Recents", "Escape", "Tab", "ArrowLeft", "Left", "ArrowUp", "Up",
        "ArrowDown", "Down", "ArrowRight", "Right", "Backspace", "Enter", "Notifications", "Quick_settings")
    Column(Modifier.fillMaxWidth().background(color(panelColor)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val keys = primary.filter { it in capabilities.keys } + if (modifiers.isNotEmpty()) capabilities.keys.filter { it.length == 1 } else emptyList()
        keys.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    Button(onClick = {
                        state.displayed?.takeIf { state.interactive }?.let { frame ->
                            actions.input(frame, listOf(ScreenAction(ScreenActionKind.Key, key = (modifiers + key).joinToString("+"))))
                            modifiers = emptySet()
                        }
                    }, enabled = state.interactive, modifier = Modifier.weight(1f).heightIn(min = 44.dp), style = ButtonStyle.Secondary) {
                        Text(labels[key] ?: key, modifier = Modifier.semantics { contentDescription = key })
                    }
                }
            }
        }
        if (capabilities.modifiers.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            capabilities.modifiers.forEach { key ->
                Button(onClick = { modifiers = if (key in modifiers) modifiers - key else modifiers + key }, enabled = state.interactive,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp), style = if (key in modifiers) ButtonStyle.Primary else ButtonStyle.Secondary) {
                    Text(when (key) { "Meta", "Command" -> "⌘"; "Control" -> "Ctrl"; "Alt" -> "Alt"; "Shift" -> "⇧"; else -> key },
                        modifier = Modifier.semantics { contentDescription = key; stateDescription = if (key in modifiers) "Selected" else "Not selected" })
                }
            }
            }
        if (modifiers.isNotEmpty()) Text("Choose a key above to send the shortcut", fontSize = 11.sp, color = color(mutedColor))
    }
}
