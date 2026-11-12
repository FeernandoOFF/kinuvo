package kinuvo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
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
import kinuvo.protocol.screen.*
import kinuvo.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DockShape = RoundedCornerShape(Radius.card)
private val StageShape = RoundedCornerShape(Radius.sheet)

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
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.space12, vertical = Spacing.space8),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space10)) {
            back?.let { RoundButton(Lucide.ArrowLeft, "Back", it) }
            Column(Modifier.weight(1f)) {
                Text(screen?.title?.ifBlank { null } ?: if (state.owner != null) "Browser" else "Computer", singleLine = true, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (tone == Tone.Neutral) color(mutedColor) else tone.color()))
                    Text(status, style = TextStyles.caption, color = if (tone == Tone.Neutral) color(mutedColor) else tone.color(), singleLine = true)
                    // Which site a Browser tab is on, before anyone takes it over.
                    screen?.currentUrl?.let { url -> Text("· " + url.substringAfter("://").substringBefore('/'), style = TextStyles.caption, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (state.ownsControl) Row(Modifier.clip(CircleShape).background(color(subtleColor))
                .clickable(enabled = state.connected && !state.pending && !state.control.draining, onClickLabel = "Hand back to Agent", role = Role.Button) { actions.takeover(false, false) }
                .heightIn(min = 44.dp).padding(horizontal = Spacing.space14, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                Icon(Lucide.Hand, Modifier.size(15.dp), tint = Tone.Success.color())
                Text("Hand back", style = TextStyles.label)
            }
            ScreenOptions(state, trackpad, fitPhone, { recenter++ }, { fitPhone = !fitPhone }, { restartTo = it }, actions) { controls = true }
        }
        Banner(state)
        pasteError?.let { Text(it, Modifier.padding(horizontal = Spacing.space16, vertical = Spacing.space4), color = Tone.Warning.color(), style = TextStyles.caption) }
        if (screen == null) EmptyStage(state, actions, Modifier.weight(1f).fillMaxWidth())
        else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.space12)) {
                val w = if (fitPhone) maxWidth.value.roundToInt() else 1280
                val h = if (fitPhone) maxHeight.value.roundToInt() else 800
                LaunchedEffect(screen.ref, state.control.generation, state.ownsControl, state.pending, w, h) {
                    if (state.ownsControl && screen.capabilities.resize && !state.pending) { delay(180); actions.resize(w, h) }
                }
                val frame = when (tone) { Tone.Success, Tone.Warning, Tone.Accent -> tone.color().copy(alpha = 0.6f); else -> color(borderColor) }
                val stroke = if (state.ownsControl) 2.dp else 1.dp
                Box(Modifier.fillMaxSize().clip(StageShape).background(color(sidebarColor)).border(stroke, frame, StageShape).padding(stroke)) {
                    LiveCanvas(state.frame, state.interactive, actions.input, actions.drawn, actions.decodeFailed, Modifier.fillMaxSize().clip(StageShape),
                        state.control.generation, "scroll" in screen.capabilities.inputs, trackpad = trackpad, recenter = recenter)
                }
            }
            if (state.ownsControl && "type" in screen.capabilities.inputs) key(state.selected, state.control.generation) {
                RemoteKeyboard(state, actions, focus, keyboard, compact = true)
            }
            Column(Modifier.fillMaxWidth().padding(Spacing.space12).shadow(12.dp, DockShape).clip(DockShape).background(color(panelColor))
                .border(1.dp, color(borderColor), DockShape).padding(Spacing.space8), verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                val capabilities = screen.capabilities
                val screensLabel = if (state.owner != null) "Tabs & address" else "Computers"
                val screensIcon = if (state.owner != null) Lucide.PanelsTopLeft else Lucide.MonitorSmartphone
                val screensCaption = if (state.owner != null) "Tabs" else "Screens"
                if (!state.ownsControl) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                    DockButton(screensIcon, screensLabel, { controls = true }, caption = screensCaption)
                    Button(onClick = { actions.takeover(true, state.control.holder != null) }, enabled = state.connected && !state.pending && !state.control.draining,
                        modifier = Modifier.weight(1f), style = ButtonStyle.Primary) {
                        Icon(Lucide.Hand, Modifier.size(16.dp))
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
                    // Equal slots, so a control turning on or off never moves the others.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val slot = Modifier.weight(1f)
                        Box(slot, contentAlignment = Alignment.Center) {
                            DockButton(if (keyboard) Lucide.KeyboardOff else Lucide.Keyboard, if (keyboard) "Hide keyboard" else "Keyboard", { keyboard = !keyboard }, caption = "Keyboard",
                                enabled = state.connected && "type" in capabilities.inputs, selected = keyboard)
                        }
                        Box(slot, contentAlignment = Alignment.Center) {
                            DockButton(if (android) Lucide.Smartphone else Lucide.Command, if (keyTray) "Hide keys" else "Keys", { keyTray = !keyTray }, caption = "Keys",
                                enabled = capabilities.keys.isNotEmpty(), selected = keyTray)
                        }
                        Box(slot, contentAlignment = Alignment.Center) {
                            DockButton(Lucide.MousePointer2, "Trackpad mode", { trackpad = !trackpad }, caption = "Trackpad", enabled = "tap" in capabilities.inputs, selected = trackpad)
                        }
                        Box(slot, contentAlignment = Alignment.Center) {
                            ClipboardButton(state, paste, desktop && "C" in capabilities.keys && copyModifier != null,
                                { sendKey("$copyModifier+C") }, { sendKey("$copyModifier+V") }, "V" in capabilities.keys)
                        }
                        switcher?.let { Box(slot, contentAlignment = Alignment.Center) { DockButton(Lucide.ArrowRightLeft, "Switch apps", { sendKey(it) }, caption = "Switch", enabled = state.interactive) } }
                        if (state.owner != null) Box(slot, contentAlignment = Alignment.Center) { DockButton(screensIcon, screensLabel, { controls = true }, caption = screensCaption) }
                    }
                }
            }
        }
    }
    BrowserRestartDialog(restartTo, state, actions) { restartTo = null }
    BottomSheet(controls, { controls = false }, header = {
        Row(Modifier.fillMaxWidth().padding(Spacing.space12), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.owner != null) "Browser" else "Computers", Modifier.weight(1f))
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
private fun ScreenOptions(state: LiveViewState, trackpad: Boolean, fitPhone: Boolean, recenter: () -> Unit, fit: () -> Unit, restart: (Boolean) -> Unit, actions: LiveActions, onScreens: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val screen = state.screen
    val empty = state.ownsControl && state.owner != null && state.presentation == null && !trackpad && screen?.capabilities?.resize != true &&
        state.error == null && state.connected
    if (empty) return
    DropdownMenu(expanded = menu, onExpandedChange = { menu = it }, panel = {
        DropdownMenuPanel {
            // In control, a Computer's dock keeps to input, so other Screens are one step away here; a Browser's dock has Tabs.
            if (state.ownsControl && state.owner == null) DropdownMenuItem(onClick = { menu = false; onScreens() }) {
                MobileMenuItem(Lucide.MonitorSmartphone, "Computers")
            }
            BrowserPresentationMenuItems(state) { menu = false; restart(it) }
            if (trackpad) DropdownMenuItem(onClick = { menu = false; recenter() }, enabled = state.interactive) {
                MobileMenuItem(Lucide.Crosshair, "Recenter pointer")
            }
            if (screen?.capabilities?.resize == true) DropdownMenuItem(onClick = { menu = false; fit() }, enabled = state.ownsControl) {
                MobileMenuItem(if (fitPhone) Lucide.Check else Lucide.Smartphone, "Fit to phone")
            }
            // Hand back sits in the header while in control.
            if (!state.ownsControl) DropdownMenuItem(onClick = { menu = false; actions.takeover(true, state.control.holder != null) },
                enabled = state.connected && !state.pending && !state.control.draining && screen != null) {
                MobileMenuItem(Lucide.Hand, if (state.control.holder != null) "Take over here" else "Take over")
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
    }) { DockButton(Lucide.ClipboardPaste, "Clipboard", { menu = true }, caption = "Clipboard", enabled = typing || shortcuts && state.interactive) }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, style = ButtonStyle.Ghost, shape = CircleShape,
        modifier = Modifier.size(44.dp).background(color(subtleColor), CircleShape)) {
        Icon(icon, Modifier.size(19.dp), contentDescription = label, tint = if (enabled) color(onBackgroundColor) else color(mutedColor))
    }
}

/** A dock control: its icon over a short [caption]; [label] is read out. */
@Composable
private fun DockButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, selected: Boolean? = null, caption: String) {
    val shape = CircleShape
    val tint = when {
        !enabled -> color(mutedColor).copy(alpha = 0.7f)
        selected == true -> color(accentColor)
        else -> color(onPanelColor)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled, style = ButtonStyle.Ghost, shape = shape,
            modifier = Modifier.size(46.dp, 40.dp).background(if (selected == true) color(accentColor).copy(alpha = 0.16f) else Color.Transparent, shape)
                .semantics { if (selected != null) stateDescription = if (selected) "On" else "Off" }) {
            Icon(icon, Modifier.size(22.dp), contentDescription = label, tint = tint)
        }
        Text(caption, Modifier.clearAndSetSemantics {}, style = TextStyles.overline, lineHeight = 13.sp, color = if (selected == true) tint else color(mutedColor), singleLine = true)
    }
}

@Composable
private fun MobileMenuItem(icon: ImageVector, label: String) {
    Row(Modifier.padding(vertical = Spacing.space6), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space12)) {
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        val keys = primary.filter { it in capabilities.keys } + if (modifiers.isNotEmpty()) capabilities.keys.filter { it.length == 1 } else emptyList()
        keys.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                row.forEach { key ->
                    KeyCap(key, enabled = state.interactive, icon = if (android && key == "Home") Lucide.Circle else KeyIcons[key]) {
                        state.displayed?.takeIf { state.interactive }?.let { frame ->
                            actions.input(frame, listOf(ScreenAction(ScreenActionKind.Key, key = (modifiers + key).joinToString("+"))))
                            modifiers = emptySet()
                        }
                    }
                }
                // A single short row spreads across the tray; longer ones keep a grid.
                if (keys.size > 5) repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (capabilities.modifiers.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            capabilities.modifiers.forEach { key ->
                KeyCap(key, enabled = state.interactive, selected = key in modifiers, icon = KeyIcons[key]) { modifiers = if (key in modifiers) modifiers - key else modifiers + key }
            }
            repeat((5 - capabilities.modifiers.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
        if (modifiers.isNotEmpty()) Text("Choose a key to send ${modifiers.joinToString("+")}+…", Modifier.padding(horizontal = Spacing.space4), style = TextStyles.overline, color = color(mutedColor))
    }
}

@Composable
private fun RowScope.KeyCap(key: String, enabled: Boolean, selected: Boolean? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.menu)
    val tint = when { !enabled -> color(mutedColor).copy(alpha = 0.5f); selected == true -> color(accentColor); else -> color(onPanelColor) }
    Box(Modifier.weight(1f).height(44.dp).clip(shape)
        .background(if (selected == true) color(accentColor).copy(alpha = 0.16f) else color(subtleColor))
        .then(if (selected == true) Modifier.border(1.dp, color(accentColor).copy(alpha = 0.5f), shape) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = key
            role = Role.Button
            if (selected != null) stateDescription = if (selected) "Selected" else "Not selected"
        }, contentAlignment = Alignment.Center) {
        icon?.let { Icon(it, Modifier.size(18.dp), tint = tint) }
            ?: Text(KeyLabels[key] ?: key.replace('_', ' '), style = TextStyles.label, color = tint, singleLine = true)
    }
}
