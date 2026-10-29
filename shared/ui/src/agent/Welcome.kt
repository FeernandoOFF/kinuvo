package kinuvo.ui.agent

import kinuvo.app.connection.*
import kinuvo.app.agent.AvatarArtboards
import kinuvo.ui.session.HeaderButton

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.ui.design.*

/** The first screen's ways to reach a Server: scan its pairing QR code, run one here, reconnect, or type its address and code. */
internal class WelcomeOptions(
    /** Opens the camera; null where this device can't scan. */
    val onScan: (() -> Unit)?,
    /** Starts the Server on this device; null where it can't run one. */
    val onLocal: (() -> Unit)?,
    val onPair: () -> Unit,
    val servers: List<Server.Remote> = emptyList(),
    val onServer: (Server.Remote) -> Unit = {},
    val url: TextFieldState = TextFieldState(),
    val code: TextFieldState = TextFieldState(),
    val name: TextFieldState = TextFieldState(),
    /** Whether the address and code form is open. */
    val manual: Boolean = false,
    val onManual: (Boolean) -> Unit = {},
    val busy: Boolean = false,
    val error: String? = null,
    /** Goes back to the Server this Device was on, e.g. after choosing to add another. */
    val onCancel: (() -> Unit)? = null,
)

/** What Kinuvo is, over its avatars, and how to connect: a hero card above on phones, beside on wide screens. */
@Composable
internal fun Welcome(options: WelcomeOptions, featured: Int, pokes: List<Int>, onPoke: (Int) -> Unit, tint: Color, insets: WindowInsets) {
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = options.manual && !options.busy) { options.onManual(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val wide = maxWidth >= 800.dp
        if (wide) Row(Modifier.fillMaxSize().windowInsetsPadding(insets).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Hero(featured, pokes, onPoke, tint, RoundedCornerShape(36.dp), Modifier.weight(1.1f).fillMaxHeight(), PaddingValues(28.dp), options.onCancel)
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Column(Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) { Panel(options, featured, true) }
            }
        } else {
            val height = maxHeight
            val top = insets.asPaddingValues().calculateTopPadding()
            // The hero shrinks while the address form is open, leaving room for it and the keyboard.
            val hero by animateDpAsState(if (options.manual) top + 150.dp else height * 0.46f)
            Column(Modifier.fillMaxSize()) {
                Hero(featured, pokes, onPoke, tint, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp), Modifier.fillMaxWidth().height(hero),
                    PaddingValues(top = top + 8.dp, start = 22.dp, end = 22.dp, bottom = 16.dp), options.onCancel)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).imePadding()
                    .padding(horizontal = 22.dp, vertical = 24.dp)) { Panel(options, featured, false) }
            }
        }
    }
}

@Composable
private fun Hero(featured: Int, pokes: List<Int>, onPoke: (Int) -> Unit, tint: Color, shape: Shape, modifier: Modifier, padding: PaddingValues, onCancel: (() -> Unit)?) {
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(tint.copy(alpha = 0.5f), tint.copy(alpha = 0.22f)))).padding(padding)) {
        Text("kinuvo", Modifier.align(Alignment.TopStart), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        onCancel?.let { Box(Modifier.align(Alignment.TopEnd)) { HeaderButton(Lucide.X, "Cancel", it) } }
        Cast(featured, pokes, onPoke, Modifier.fillMaxSize().padding(top = 28.dp))
    }
}

/** What each of the four Agents says when it's the one introducing itself. */
private val Introductions = listOf("I remember what matters to you.", "I check in while you sleep.", "I research the web for you.", "I live on your own Server.")

/** One Agent introducing itself in a speech bubble, the rest in a row below to tap and hear from. */
@Composable
private fun Cast(featured: Int, pokes: List<Int>, onPoke: (Int) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val size = minOf(maxHeight * 0.42f, maxWidth * 0.5f, 220.dp)
        // Folded away for the address form: just the Agent and what it says.
        if (maxHeight < 200.dp) return@BoxWithConstraints Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
            Floating(AvatarArtboards[featured], 72.dp, Gaze.Right, pokes[featured], 0f, Modifier.sharedAgent())
            SpeechBubble(Introductions[featured], Modifier.weight(1f, fill = false), RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp))
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                SpeechBubble(Introductions[featured], Modifier.widthIn(max = 300.dp), RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                AnimatedContent(featured, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.8f)) }) { index ->
                    Floating(AvatarArtboards[index], size, Gaze.Forward, pokes[index], 0f,
                        Modifier.then(if (index == featured) Modifier.sharedAgent() else Modifier).clip(CircleShape).clickable(role = Role.Button) { onPoke(index) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarArtboards.take(Introductions.size).forEachIndexed { index, artboard ->
                    val on = index == featured
                    val ring by animateDpAsState(if (on) 2.dp else 0.dp)
                    Box(Modifier.size(48.dp).clip(CircleShape).background(color(backgroundColor).copy(alpha = if (on) 0.35f else 0.15f))
                        .border(ring, color(onBackgroundColor).copy(alpha = 0.6f), CircleShape)
                        .clickable(role = Role.Button) { onPoke(index) }.semantics { selected = on; contentDescription = artboard },
                        contentAlignment = Alignment.Center) {
                        RiveAvatar(BundledAvatar, Modifier.size(34.dp).alpha(if (on) 1f else 0.6f), if (on) Gaze.Forward else Gaze.Up, artboard, 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(options: WelcomeOptions, featured: Int, wide: Boolean) {
    AnimatedContent(options.manual, transitionSpec = { fadeIn() togetherWith fadeOut() }) { manual ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (manual) Manual(options) else Choices(options, featured, wide)
        }
    }
}

@Composable
private fun ColumnScope.Choices(options: WelcomeOptions, featured: Int, wide: Boolean) {
    Text("Your agents, on your own Server.", style = displayStyle(if (wide) 48.sp else 34.sp))
    Text(if (options.onLocal != null) "Connect to your Kinuvo Server, or set one up right here." else "Connect to Kinuvo on your computer.",
        color = color(mutedColor), fontSize = if (wide) 18.sp else 16.sp)
    Spacer(Modifier.height(8.dp))
    options.error?.let { Text(it, color = color(destructiveColor), fontSize = 14.sp) }
    val manual = { options.onManual(true) }
    when {
        options.onScan != null -> {
            Cta(OnboardingAction(if (options.busy) "Connecting…" else "Scan QR code", run = options.onScan), options.busy, Modifier.fillMaxWidth(), Lucide.ScanLine)
            options.onLocal?.let { SecondaryCta("Set up on this device", Lucide.Laptop, it, !options.busy) }
        }
        options.onLocal != null -> {
            Cta(OnboardingAction(if (options.busy) "Starting…" else "Set up on this device", run = options.onLocal), options.busy, Modifier.fillMaxWidth(), Lucide.Laptop)
            SecondaryCta("Connect to a Server", Lucide.Server, manual, !options.busy)
        }
        else -> Cta(OnboardingAction("Connect to a Server", run = manual), options.busy, Modifier.fillMaxWidth(), Lucide.Server)
    }
    if (options.servers.isNotEmpty()) RowGroup("Your servers", Modifier.padding(top = 8.dp)) {
        options.servers.forEach { server -> GroupRow(server.label, { options.onServer(server) }, server.address, Lucide.Server, enabled = !options.busy) }
    }
    QrHelp(manual.takeIf { options.onScan != null }, !options.busy)
}

/** Small links under the buttons: typing the address and code instead, when [onManual] is set, and where the pairing QR code is. */
@Composable
private fun QrHelp(onManual: (() -> Unit)?, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        onManual?.let {
            Text("Enter address and code", Modifier.clip(CircleShape).clickable(enabled, role = Role.Button, onClick = it).padding(horizontal = 14.dp, vertical = 8.dp),
                color = color(onBackgroundColor), style = com.composeunstyled.LocalTextStyle.current.copy(fontSize = 15.sp, textDecoration = TextDecoration.Underline))
        }
        Row(Modifier.clip(CircleShape).clickable(role = Role.Button) { open = !open }.semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Where is the QR code?", color = color(mutedColor), fontSize = 15.sp)
            Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, Modifier.size(15.dp), tint = color(mutedColor))
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            RowGroup {
                GroupRow("In the Kinuvo app", subtitle = "On the computer running your Server: Settings › Servers & devices › Pair a device.", icon = Lucide.Laptop, chevron = false)
                GroupRow("In a terminal", subtitle = "Run kinuvo pair where the Server runs. Add --manage to set it up from here.", icon = Lucide.SquareTerminal, chevron = false)
            }
        }
    }
}

@Composable
private fun ColumnScope.Manual(options: WelcomeOptions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HeaderButton(Lucide.ArrowLeft, "Back", { options.onManual(false) }, enabled = !options.busy)
        Text("Connect to a Server", style = displayStyle(26.sp))
    }
    Text("Use the address and code your Server shows when you pair a device, or paste its pairing link.", color = color(mutedColor))
    RowGroup {
        FieldRow("Server address or pairing link") { Input(options.url, "https://your-server:8787") }
        FieldRow("Pairing code") { Input(options.code, "Code from the Server") }
        FieldRow("Name on this device") { Input(options.name, "Home server") }
    }
    if (options.servers.isNotEmpty()) RowGroup("Your servers") {
        options.servers.forEach { server -> GroupRow(server.label, { options.onServer(server) }, server.address, Lucide.Server, enabled = !options.busy) }
    }
    options.error?.let { Text(it, color = color(destructiveColor), fontSize = 14.sp) }
    Spacer(Modifier.height(4.dp))
    Cta(OnboardingAction(if (options.busy) "Connecting…" else "Connect", options.url.text.isNotBlank() && options.code.text.isNotBlank(), options.onPair),
        options.busy, Modifier.fillMaxWidth())
}

/** A quieter companion to [Cta]. */
@Composable
internal fun SecondaryCta(label: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(color(subtleColor)).clickable(enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = color(onBackgroundColor).copy(alpha = if (enabled) 1f else 0.4f))
    }
}
