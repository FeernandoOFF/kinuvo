package kinuvo.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
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
import kinuvo.ui.KinuvoApp
import kinuvo.ui.startKinuvo
import kinuvo.ui.design.LocalWindowChrome
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
            port = s.start("0.0.0.0", HarnessServer.EMBEDDED_PORTS)
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
    startKinuvo(module {
        single<EmbeddedServer> { HarnessEmbeddedServer }
        single<ComputerDriver> { CuaComputerDriver() }
    })
    application {
        Window(onCloseRequest = ::exitApplication, title = "Kinuvo", state = rememberWindowState(width = 1100.dp, height = 760.dp)) {
            CompositionLocalProvider(LocalWindowChrome provides rememberWindowChrome()) { KinuvoApp() }
        }
    }
}
