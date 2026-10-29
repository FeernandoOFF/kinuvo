package kinuvo.ui.connection

import kinuvo.app.connection.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import kinuvo.protocol.Links
import kinuvo.protocol.PairLink
import kotlinx.coroutines.delay

/**
 * Pairs this Device with a Server, or reconnects to a saved one, on the same screen the onboarding starts with.
 * [connecting] is the Server being reached and [error] why the last try failed. [onCancel], when set, goes back to the
 * Server this Device was on, [from]: when that's the one on this Device, only other Servers are offered.
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
    connecting: Server? = null,
    onCancel: (() -> Unit)? = null,
    from: Server? = null,
) {
    val url = rememberTextFieldState(pending?.server ?: initialUrl)
    val code = rememberTextFieldState(pending?.code.orEmpty())
    val name = rememberTextFieldState(initialName)
    var scanError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        snapshotFlow { url.text.toString() }.collect { text ->
            Links.parsePair(text)?.let { link -> url.setTextAndPlaceCursorAtEnd(link.server); code.setTextAndPlaceCursorAtEnd(link.code) }
        }
    }
    val elsewhere = from == Server.Embedded
    val scan = rememberQrScanner { text ->
        val link = Links.parsePair(text)
        if (link == null) scanError = "That QR code isn't a Kinuvo pairing code. Pair a device on your Server and scan the code it shows."
        else { scanError = null; onConnect(link.server, link.code, name.text.toString()) }
    }
    // Leaving this Device's own Server, another one is what's wanted: without a camera, its address and code.
    var manual by remember { mutableStateOf(pending != null || elsewhere && scan == null) }
    LaunchedEffect(pending) { pending?.let { url.setTextAndPlaceCursorAtEnd(it.server); code.setTextAndPlaceCursorAtEnd(it.code); manual = true } }
    // The same Agents take turns introducing themselves as on the onboarding's first screen.
    val pokes = remember { mutableStateListOf(0, 0, 0, 0) }
    var featured by remember { mutableIntStateOf(0) }
    var touring by remember { mutableStateOf(true) }
    if (touring) LaunchedEffect(featured) { delay(4500); featured = (featured + 1) % pokes.size; pokes[featured] += 1 }
    val tint by animateColorAsState(AvatarInk[featured], tween(700))
    Welcome(WelcomeOptions(
        onScan = scan,
        onLocal = { onSelectServer(Server.Embedded) }.takeIf { Server.Embedded in servers && !elsewhere },
        onPair = { scanError = null; onConnect(url.text.toString(), code.text.toString(), name.text.toString()) },
        servers = servers.filterIsInstance<Server.Remote>(),
        onServer = { server -> if (server.token != null) onSelectServer(server) else { url.setTextAndPlaceCursorAtEnd(server.url); name.setTextAndPlaceCursorAtEnd(server.name.orEmpty()); manual = true } },
        url = url, code = code, name = name, manual = manual, onManual = { manual = it },
        busy = connecting != null, error = scanError ?: error, onCancel = onCancel,
    ), featured, pokes, { touring = false; featured = it; pokes[it] += 1 }, tint, WindowInsets.safeDrawing)
}
