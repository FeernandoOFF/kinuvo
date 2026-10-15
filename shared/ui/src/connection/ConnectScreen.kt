package kinuvo.ui.connection

import kinuvo.app.connection.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.draw.clip
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.Links
import kinuvo.protocol.PairLink
import kinuvo.ui.design.*

/**
 * Pairs this Device with a Server, or reconnects to a saved one. The link between this Device and the Server shows
 * where it stands: idle, [connecting] (the Server being reached) or failed with [error]. [onCancel], when set, goes
 * back to the Server this Device was on.
 */
@Composable
internal fun ConnectScreen(
    error: String?,
    onConnect: (url: String, code: String, name: String) -> Unit,
    servers: List<Server>,
    onSelectServer: (Server) -> Unit,
    initialUrl: String = "",
    pending: PairLink? = null,
    initialName: String = "",
    onRenameServer: (Server.Remote, String) -> Unit = { _, _ -> },
    onForgetServer: (Server.Remote) -> Unit = {},
    connecting: Server? = null,
    onCancel: (() -> Unit)? = null,
) {
    val url = rememberTextFieldState(pending?.server ?: initialUrl)
    val code = rememberTextFieldState(pending?.code.orEmpty())
    val name = rememberTextFieldState(initialName)
    var naming by remember { mutableStateOf(initialName.isNotBlank()) }
    var managing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        pending?.let { url.setTextAndPlaceCursorAtEnd(it.server); code.setTextAndPlaceCursorAtEnd(it.code) }
    }
    LaunchedEffect(url) {
        snapshotFlow { url.text.toString() }.collect { text ->
            Links.parsePair(text)?.let { link -> url.setTextAndPlaceCursorAtEnd(link.server); code.setTextAndPlaceCursorAtEnd(link.code) }
        }
    }
    val busy = connecting != null
    val connect = OnboardingAction(if (busy) "Connecting…" else "Connect", url.text.isNotBlank() && code.text.isNotBlank()) {
        onConnect(url.text.toString(), code.text.toString(), name.text.toString())
    }
    val glow = color(accentColor)
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor)).drawBehind {
        drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), glow.copy(alpha = 0f)), Offset(size.width / 2, 120.dp.toPx()), size.minDimension * 0.8f))
    }.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        val compact = maxWidth < 600.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                onCancel?.let { HeaderButton(Lucide.ArrowLeft, "Back to your server", it, enabled = !busy) }
            }
            Column(Modifier.weight(1f).widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
                Spacer(Modifier.height(if (compact) 8.dp else 40.dp))
                PairingLink(if (compact) Lucide.Smartphone else Lucide.Laptop, busy, error != null && !busy)
                Spacer(Modifier.height(28.dp))
                Text("Connect a server", style = displayStyle(if (compact) 30.sp else 38.sp))
                Spacer(Modifier.height(8.dp))
                Text(
                    connecting?.let { "Reaching ${it.label}…" } ?: "Paste the pairing link from your server, or scan its QR code with your camera.",
                    color = color(mutedColor), fontSize = 16.sp, lineHeight = 23.sp,
                )
                Spacer(Modifier.height(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConnectField("Address or pairing link") { Input(url, "https://home.example:8787") }
                    ConnectField("Pairing code") { Input(code, "From kinuvo pair") }
                    AnimatedVisibility(naming, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        ConnectField("Name on this device") { Input(name, "Home") }
                    }
                    if (!naming) Text("Name this server", Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { naming = true }.padding(vertical = 4.dp),
                        color = color(mutedColor), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (error != null && !busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Lucide.CircleAlert, Modifier.padding(top = 2.dp).size(16.dp), tint = color(destructiveColor))
                        Text(error, color = color(destructiveColor), fontSize = 14.sp)
                    }
                }
                if (servers.isNotEmpty()) {
                    Spacer(Modifier.height(32.dp))
                    Text("Your servers", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        servers.forEach { server ->
                            SavedServer(server, connecting == server, enabled = !busy, onManage = { managing = if (managing == server.key) null else server.key }) {
                                if (server is Server.Remote && server.token == null) {
                                    url.setTextAndPlaceCursorAtEnd(server.url)
                                    if (server.name != null) { name.setTextAndPlaceCursorAtEnd(requireNotNull(server.name)); naming = true }
                                    code.setTextAndPlaceCursorAtEnd("")
                                } else onSelectServer(server)
                            }
                            if (server is Server.Remote && managing == server.key) Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                                RemoteServerControls(server, onRenameServer, onForgetServer)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("No link? Run kinuvo pair on the server, or open Settings, then Connection, on a device that's already paired.",
                    color = color(mutedColor), fontSize = 13.sp, lineHeight = 19.sp)
                if (!compact) {
                    Spacer(Modifier.height(28.dp))
                    Cta(connect, busy, Modifier.fillMaxWidth())
                }
            }
            if (compact) Cta(connect, busy, Modifier.fillMaxWidth())
        }
    }
}

/** This Device and the Server, joined by dots that run while [connecting] and break on [failed]. */
@Composable
private fun PairingLink(device: ImageVector, connecting: Boolean, failed: Boolean) {
    val accent = color(accentColor)
    val muted = color(mutedColor)
    val danger = color(destructiveColor)
    val wave by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
    Row(Modifier.fillMaxWidth().semantics { contentDescription = when { connecting -> "Connecting"; failed -> "Couldn't connect"; else -> "Not connected" } },
        verticalAlignment = Alignment.CenterVertically) {
        LinkEnd(device, accent)
        Row(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            repeat(Dots) { i ->
                val middle = i == Dots / 2
                if (failed && middle) Icon(Lucide.X, Modifier.size(18.dp), tint = danger)
                else {
                    // A pulse travels from this Device to the Server.
                    val lit = if (connecting) (1f - ((wave * (Dots + 2) - i).let { if (it < 0) 99f else it }) / 2f).coerceIn(0f, 1f) else 0f
                    val tint by animateColorAsState(if (failed) danger.copy(alpha = 0.35f) else muted.copy(alpha = 0.45f))
                    Box(Modifier.size(6.dp).graphicsLayer { scaleX = 1f + lit * 0.6f; scaleY = 1f + lit * 0.6f }
                        .background(lerp(tint, accent, lit), CircleShape))
                }
            }
        }
        LinkEnd(Lucide.Server, if (failed) danger else if (connecting) accent else muted)
    }
}

private const val Dots = 7

@Composable
private fun LinkEnd(icon: ImageVector, tint: Color) {
    val ink by animateColorAsState(tint)
    Box(Modifier.size(64.dp).background(ink.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, Modifier.size(28.dp), tint = ink)
    }
}

@Composable
private fun SavedServer(server: Server, connecting: Boolean, enabled: Boolean, onManage: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(color(panelColor)).clickable(enabled, role = Role.Button, onClick = onClick).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(color(subtleColor), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (server is Server.Embedded) Lucide.Laptop else Lucide.Server, Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(server.label, fontWeight = FontWeight.Bold, singleLine = true, overflow = TextOverflow.Ellipsis)
            Text(when {
                server is Server.Embedded -> "Your data stays here"
                server is Server.Remote && server.token == null -> "Needs a new pairing code"
                server is Server.Remote && server.name != null -> server.address
                else -> "Paired"
            }, color = color(mutedColor), fontSize = 13.sp, singleLine = true, overflow = TextOverflow.Ellipsis)
        }
        when {
            connecting -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Spinner() }
            server is Server.Remote -> IconButton(onClick = onManage, enabled = enabled, style = ButtonStyle.Ghost, shape = CircleShape, modifier = Modifier.size(36.dp)) {
                Icon(Lucide.Ellipsis, Modifier.size(18.dp), tint = color(mutedColor), contentDescription = "Manage ${server.label}")
            }
            else -> Icon(Lucide.ChevronRight, Modifier.padding(end = 8.dp).size(16.dp), tint = color(mutedColor))
        }
    }
}

@Composable
private fun ConnectField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); content() }
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)
