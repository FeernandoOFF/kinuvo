package kinuvo.ui.connection

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*
import kinuvo.ui.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.panelColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.mutedColor
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * A pairing code for another Device: a QR code to scan, with the link to share or copy, for the Kinuvo app or a
 * browser. Picking an [onSelect] address matters when the other Device can only reach the Server on some of them.
 */
@Composable
internal fun PairingPanel(info: PairingInfo, selected: String?, onSelect: (String) -> Unit, onRefresh: () -> Unit, error: String? = null) {
    var browser by remember { mutableStateOf(false) }
    val base = selected?.takeIf { it in info.bases } ?: info.bases.firstOrNull()
    val remaining by produceState((info.expiresAt - Clock.System.now()).inWholeSeconds, info.expiresAt) {
        while (true) { value = (info.expiresAt - Clock.System.now()).inWholeSeconds; delay(1000) }
    }
    val share = rememberShare()
    val link = base?.let { if (browser) info.webLink(it) else info.appLink(it) }
    val live = remaining > 0
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Column(Modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Segmented(listOf("Kinuvo app", "Browser"), if (browser) 1 else 0) { browser = it == 1 }
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.size(212.dp).clip(shape).background(if (live && link != null) Color.White else color(subtleColor), shape), contentAlignment = Alignment.Center) {
            when {
                link == null -> Text("No address the other device can reach. Use this server's IP or VPN address.", Modifier.padding(20.dp), color = color(mutedColor), fontSize = 13.sp, textAlign = TextAlign.Center)
                live -> Image(rememberQrCodePainter(link), "Pairing QR code for ${base.substringAfter("://")}", Modifier.fillMaxSize().padding(18.dp))
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Lucide.TimerOff, Modifier.size(28.dp), tint = color(mutedColor))
                    Text("This code expired", color = color(mutedColor))
                    Button(onRefresh, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text("Get a new code") }
                }
            }
        }
        if (link != null && live) {
            Text(if (browser) "Scan with the other device's camera, or open the link in its browser." else "Scan with the other device's camera, or open the link there.",
                color = color(mutedColor), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                share?.let { send -> Button({ send(link) }, Modifier.weight(1f), style = ButtonStyle.Primary) { ActionLabel(Lucide.Share, "Share link") } }
                CopyAction("Copy link", link, Modifier.weight(1f))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (base != null && info.bases.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reachable at", Modifier.weight(1f), color = color(mutedColor), fontSize = 13.sp)
                Picker(info.bases, base, { it.substringAfter("://") }, onSelect)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (live) "Works once, for ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}" else "Expired",
                    Modifier.weight(1f), color = color(mutedColor), fontSize = 13.sp)
                if (live) Button(onRefresh, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { ActionLabel(Lucide.RefreshCw, "New code") }
            }
            if (live) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Code ${info.code.take(6)}…${info.code.takeLast(4)}", Modifier.weight(1f), style = MonoStyle, color = color(mutedColor), fontSize = 12.sp)
                CopyButton(info.code)
            }
        }
        if (info.management) Pill("Can change server settings and answer approvals", Modifier.fillMaxWidth(), tone = Tone.Warning, icon = Lucide.ShieldAlert)
        error?.let { Pill(it, Modifier.fillMaxWidth(), tone = Tone.Danger, icon = Lucide.CircleX) }
    } }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(color(subtleColor)).padding(3.dp)) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            Box(Modifier.weight(1f).height(32.dp).clip(CircleShape).background(if (on) color(panelColor) else Color.Transparent)
                .clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = on }, contentAlignment = Alignment.Center) {
                Text(option, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) color(onBackgroundColor) else color(mutedColor))
            }
        }
    }
}

@Composable
private fun CopyAction(label: String, text: String, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Button({ clipboard.setText(AnnotatedString(text)); copied = true }, modifier, style = ButtonStyle.Outlined) {
        ActionLabel(if (copied) Lucide.Check else Lucide.Copy, if (copied) "Copied" else label)
    }
}

@Composable
private fun ActionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Icon(icon, Modifier.size(15.dp))
    Spacer(Modifier.width(6.dp))
    Text(label, singleLine = true)
}

@Composable
internal fun RemoteServerControls(server: Server.Remote, onRename: (Server.Remote, String) -> Unit, onForget: (Server.Remote) -> Unit) {
    key(server.key, server.name) {
        val name = rememberTextFieldState(server.name.orEmpty())
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(server.url, color = color(mutedColor), fontSize = 12.sp)
            FormField("Name on this Device") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Input(name, server.address) }
                    Button({ onRename(server, name.text.toString()) }, enabled = name.text.toString() != server.name.orEmpty(), style = ButtonStyle.Outlined) { Text("Save") }
                }
            }
            ConfirmAction("Forget server", "Unpair this Device and remove this Server here?", { onForget(server) })
        }
    }
}

@Composable
internal fun ConfirmAction(label: String, question: String, onConfirm: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (!confirming) Button({ confirming = true }, style = ButtonStyle.Outlined) { Text(label) }
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ confirming = false; onConfirm() }, style = ButtonStyle.Outlined) { Text("Confirm") }
            Button({ confirming = false }, style = ButtonStyle.Ghost) { Text("Cancel") }
        }
    }
}

/** This Device's saved Servers, one open at a time ([SettingsState.server], else the connected one), and pairing for the connected Server. */
@Composable
internal fun ConnectionSettingsPane(p: Pane) {
    DetailPane(p.back, "Connection") {
        val connected = p.ui.server
        val remotes = p.ui.servers.filterIsInstance<Server.Remote>()
        val shown = remotes.firstOrNull { it.key == p.ui.state.server } ?: connected as? Server.Remote
        if (p.ui.servers.size > 1) DetailGroup("Servers on this device") {
            p.ui.servers.forEach { server ->
                ListRow(server == shown, { if (server is Server.Remote) p.actions.onPickServer(server.key) }, enabled = server is Server.Remote || server == connected) {
                    Icon(if (server is Server.Embedded) Lucide.Laptop else Lucide.Server, Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.label)
                        if (server is Server.Remote) Text(server.address, color = color(mutedColor), fontSize = 12.sp)
                    }
                    if (server == connected) Pill("Connected", tone = Tone.Success)
                }
            }
        }
        if (shown != null) DetailGroup(shown.label) {
            RemoteServerControls(shown, p.actions.onRenameServer, p.actions.onForgetServer)
            if (shown == connected && shown.token != "browser-session")
                ConfirmAction("Rotate Device credential", "Replace this Device's credential and disconnect its other connections?", p.actions.onRotateToken)
        } else Text(connected?.label ?: "No Server connected")
        if (connected is Server.Embedded && p.ui.sharing != null) DetailGroup("Reach this Server from other Devices") {
            Checkbox(p.ui.sharing, p.actions.onShare) { Text("Listen on the local network and Tailscale") }
            Text("Other Devices pair with a code from here, then use this Server and its Computers. Keep it off on networks you don't trust.",
                color = color(mutedColor), fontSize = 12.sp)
        }
        if (p.ui.capabilities.supports(ServerFeature.Pairing)) {
            val on = connected?.label?.let { " with $it" }.orEmpty()
            if (p.ui.management) DetailGroup("Pair a device$on") {
                val info = p.ui.pairing
                if (info != null) PairingPanel(info, p.ui.pairingBase, p.actions.onPairingBase, { p.actions.onRefreshPairing(true) }, p.ui.pairingError)
                else {
                    p.ui.pairingError?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
                    Button({ p.actions.onRefreshPairing(false) }, style = ButtonStyle.Outlined) { Text("Get pairing code") }
                }
                ConfirmAction("Pair a management Device", "This Device will be able to change Server configuration, pair other Devices and answer Approvals.", p.actions.onManagementPairing)
            }
            DetailGroup("Paired devices$on") { PairedDevices(p.ui.devices, p.actions.onRevokeDevice, p.ui.management) }
        }
    }
}

@Composable
internal fun PairedDevices(state: DevicesState, onRevoke: (PairedDeviceInfo) -> Unit, management: Boolean = true) {
    state.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
    val devices = state.devices
    if (devices == null) { if (state.error == null) Spinner(); return }
    if (devices.isEmpty()) Text("No paired Devices.", color = color(mutedColor))
    devices.forEach { device -> key(device.id) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(device.name + if (device.current) " · This device" else "")
            Text("Paired ${device.pairedAt.substringBefore('T')}", color = color(mutedColor), fontSize = 12.sp)
            Text(if (device.connected) "Connected" else device.lastSeen?.let { "Last seen ${it.take(16).replace('T', ' ')}" } ?: "Not seen yet", color = color(mutedColor), fontSize = 12.sp)
            if (device.tools.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                device.tools.forEach { kind -> Pill(kind.name, icon = when (kind) {
                    DeviceToolKind.Contacts -> Lucide.Contact
                    DeviceToolKind.Workouts -> Lucide.Dumbbell
                    DeviceToolKind.Sleep -> Lucide.Moon
                    DeviceToolKind.Computer -> Lucide.MousePointerClick
                }) }
            }
            Text(if (device.management) "Management access" else "Ordinary access", color = color(mutedColor), fontSize = 12.sp)
            if (management || device.current) ConfirmAction("Revoke", "${if (device.current) "This Device" else device.name} will need to pair again.", { onRevoke(device) })
            HorizontalSeparator(Modifier.padding(vertical = 6.dp))
        }
    } }
}
