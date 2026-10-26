package kinuvo.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kinuvo.harness.server.Harness
import kinuvo.harness.server.HarnessServer
import kinuvo.app.computer.ComputerDriver
import kinuvo.app.connection.EmbeddedServer
import kinuvo.harness.cua.DesktopComputer
import kinuvo.protocol.ComputerRequest
import kinuvo.app.connection.Endpoint
import kinuvo.app.connection.pendingLink
import kinuvo.app.navigation.Route
import kinuvo.ui.KinuvoApp
import kinuvo.ui.startKinuvo
import kinuvo.ui.design.LocalWindowChrome
import kinuvo.ui.design.appIcon
import kinuvo.ui.design.trayIcon
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.dsl.module

/** Runs the Kinuvo server (the harness) inside the app; phones on the LAN can pair with it. */
private object HarnessEmbeddedServer : EmbeddedServer {
    private val mutex = Mutex()
    private var server: HarnessServer? = null
    private var port = 0
    /** One credential for the app's lifetime, so reconnecting stays the same Device, e.g. with its Computer. */
    private var token: String? = null

    private suspend fun started(): HarnessServer = mutex.withLock {
        server ?: HarnessServer(Harness()).also { s ->
            port = s.start("0.0.0.0", System.getenv("KINUVO_EMBEDDED_PORT")?.toInt()?.let(::listOf) ?: HarnessServer.EMBEDDED_PORTS)
            s.advertise()
            Runtime.getRuntime().addShutdownHook(Thread { s.stop() })
            server = s
        }
    }

    override suspend fun endpoint(): Endpoint {
        val server = started()
        val token = mutex.withLock { token ?: server.auth.sessionToken().also { token = it } }
        return Endpoint("ws://127.0.0.1:$port/ws", token)
    }

}

/** This computer's screen for computer use, offered under This Device's tools like a phone's. */
private class CuaComputerDriver(private val desktop: DesktopComputer = DesktopComputer()) : ComputerDriver {
    override val ready get() = desktop.ready
    override val requirement = "Needs cua-driver (github.com/trycua/cua) with Accessibility and Screen Recording: run `cua-driver permissions grant`."
    override suspend fun perform(request: ComputerRequest) = desktop.perform(request)
}

fun main() {
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    startKinuvo(module {
        single<EmbeddedServer> { HarnessEmbeddedServer }
        single<ComputerDriver> { CuaComputerDriver() }
    })
    application {
        val state = rememberWindowState(width = 1100.dp, height = 760.dp)
        var frame by remember { mutableStateOf<ComposeWindow?>(null) }
        val show: () -> Unit = {
            state.isMinimized = false
            frame?.run { toFront(); requestFocus() }
        }
        trayIcon()?.let { icon ->
            Tray(icon, tooltip = "Kinuvo", onAction = show) {
                Item("Open Kinuvo", onClick = show)
                Item("New chat") { show(); pendingLink.value = Route.Chat(compose = true) }
                Separator()
                Item("Quit Kinuvo", onClick = ::exitApplication)
            }
        }
        Window(onCloseRequest = ::exitApplication, title = "Kinuvo", icon = appIcon(), state = state) {
            LaunchedEffect(window) { frame = window }
            CompositionLocalProvider(LocalWindowChrome provides rememberWindowChrome()) { KinuvoApp() }
        }
    }
}
