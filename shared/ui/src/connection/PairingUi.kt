package kinuvo.ui.connection

import kinuvo.app.navigation.SettingsSection
import kinuvo.ui.device.icon
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
import com.composables.ui.theme.*
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

/** This Device's saved Servers, one open at a time ([SettingsState.server], else the connected one), and pairing for the connected Server. */
@Composable
internal fun ConnectionSettingsPane(p: Pane) {
    DetailPane(p.back, SettingsSection.Connection.title) {
        val connected = p.ui.server
        val remotes = p.ui.servers.filterIsInstance<Server.Remote>()
        val shown = remotes.firstOrNull { it.key == p.ui.state.server } ?: connected as? Server.Remote
        if (p.ui.servers.size > 1) RowGroup("Servers on this device") {
            p.ui.servers.forEach { server ->
                GroupRow(server.label, { if (server is Server.Remote) p.actions.onPickServer(server.key) }, subtitle = (server as? Server.Remote)?.address,
                    icon = if (server is Server.Embedded) Lucide.Laptop else Lucide.Server, selected = server == shown && p.ui.servers.size > 1,
                    enabled = server is Server.Remote || server == connected, chevron = false) {
                    if (server == connected) Pill("Connected", tone = Tone.Success)
                }
            }
        }
        if (shown != null) key(shown.key, shown.name) {
            val name = rememberTextFieldState(shown.name.orEmpty())
            RowGroup(shown.label) {
                GroupBox {
                    Text("Name on this Device", color = color(mutedColor), fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Input(name, shown.address) }
                        Button({ p.actions.onRenameServer(shown, name.text.toString()) }, enabled = name.text.toString() != shown.name.orEmpty(),
                            style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text("Save") }
                    }
                    Text(shown.url, color = color(mutedColor), fontSize = 12.sp)
                }
                if (shown == connected) ConfirmRow("Rotate Device credential", Lucide.RotateCcwKey, "Rotate this Device's credential?", p.actions.onRotateToken, "Rotate",
                    destructive = false, detail = "Its other connections disconnect and need the new credential.")
                ConfirmRow("Forget server", Lucide.Trash2, "Forget ${shown.label}?", { p.actions.onForgetServer(shown) }, "Forget",
                    detail = "This Device unpairs and removes the Server. You can pair again with a new code.")
            }
        } else Text(connected?.label ?: "No Server connected")
        if (connected is Server.Embedded && p.ui.sharing != null) RowGroup("Reach this Server from other Devices") {
            GroupRow("Local network and Tailscale", subtitle = "Other Devices pair with a code from here. Keep it off on networks you don't trust.", icon = Lucide.Wifi, chevron = false) {
                Switch(p.ui.sharing, p.actions.onShare)
            }
        }
        if (p.ui.capabilities.supports(ServerFeature.Pairing)) {
            PairedDevices(p.ui.devices, p.actions.onRevokeDevice, p.ui.management, "Devices using ${connected?.label ?: "this Server"}") {
                if (p.ui.management) {
                    val info = p.ui.pairing
                    if (info != null) GroupBox { PairingPanel(info, p.ui.pairingBase, p.actions.onPairingBase, { p.actions.onRefreshPairing(true) }, p.ui.pairingError) }
                    else {
                        p.ui.pairingError?.let { GroupBox { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) } }
                        ActionRow("Pair a device", Lucide.Plus, { p.actions.onRefreshPairing(false) }, subtitle = "A phone, computer or browser, with a one-time code")
                    }
                    ConfirmRow("Pair a management device", Lucide.ShieldPlus, "Pair a management device?", p.actions.onManagementPairing, "Get code", destructive = false,
                        detail = "It will be able to change Server configuration, pair other devices and answer Approvals.")
                }
            }
            Text("Revoking takes effect right away; the device needs a new code to connect again.", Modifier.padding(horizontal = 6.dp), fontSize = 12.sp, color = color(mutedColor))
        }
    }
}

/** One row per paired Device: what it is, when it was last seen, and Revoke. */
@Composable
internal fun PairedDevices(state: DevicesState, onRevoke: (PairedDeviceInfo) -> Unit, management: Boolean = true, title: String = "Paired devices",
    actions: @Composable ColumnScope.() -> Unit = {}) {
    val devices = state.devices
    RowGroup(title) {
        state.error?.let { GroupBox { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) } }
        when {
            devices == null -> if (state.error == null) GroupBox { Spinner() }
            devices.isEmpty() -> GroupRow("No paired devices yet", tint = color(mutedColor))
            else -> devices.forEach { device -> key(device.id) {
                var asking by remember { mutableStateOf(false) }
                val seen = if (device.connected) "Connected" else device.lastSeen?.let { "Seen ${it.take(16).replace('T', ' ')}" } ?: "Not seen yet"
                GroupRow(device.name + if (device.current) " · This device" else "",
                    subtitle = listOfNotNull(seen, "Management".takeIf { device.management }).joinToString(" · "),
                    icon = if (device.management) Lucide.ShieldCheck else Lucide.Smartphone, chevron = false) {
                    device.tools.forEach { Icon(it.icon(), Modifier.size(14.dp), tint = color(mutedColor), contentDescription = it.name) }
                    if (management || device.current) Button({ asking = true }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small,
                        contentColor = color(destructiveColor)) { Text("Revoke") }
                }
                ConfirmPopover(asking, "Revoke ${if (device.current) "this Device" else device.name}?", "It will need to pair again. Paired ${device.pairedAt.substringBefore('T')}.",
                    "Revoke", true, { asking = false }) { asking = false; onRevoke(device) }
            } }
        }
        actions()
    }
}
