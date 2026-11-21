package kinuvo.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.LocalWindowChromeInsets
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.windowDragArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import kinuvo.ui.design.WindowChrome
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberWindowState
import kinuvo.server.jvm.JvmAgentRuntime
import kinuvo.server.jvm.JvmServerHost
import kinuvo.server.jvm.LocalServer
import kinuvo.server.jvm.LoginServiceSettings
import kinuvo.server.jvm.kinuvoHome
import kinuvo.app.computer.ComputerDriver
import kinuvo.client.TailcatForwarder
import kinuvo.tailcat.Tailcat
import kinuvo.app.connection.SharingOption
import java.util.prefs.Preferences
import java.security.MessageDigest
import kinuvo.app.connection.EmbeddedServer
import java.io.File
import kinuvo.plugins.cua.DesktopComputer
import kinuvo.protocol.computer.ComputerRequest
import kinuvo.app.connection.Endpoint
import kinuvo.app.connection.pendingLink
import kinuvo.app.navigation.Route
import kinuvo.ui.KinuvoApp
import kinuvo.ui.startKinuvo
import kinuvo.ui.design.LocalWindowChrome
import kinuvo.ui.design.appIcon
import kinuvo.ui.design.trayIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.dsl.module
import kotlin.system.exitProcess

/** Runs the Kinuvo server (the harness) inside the app; phones on the LAN can pair with it. */
private object HarnessEmbeddedServer : EmbeddedServer {
    private fun preferenceKey() = MessageDigest.getInstance("SHA-256").digest(kinuvoHome().toString().toByteArray()).joinToString("") { "%02x".format(it) }
    private val prefs = Preferences.userRoot().node("kinuvo/tailcat")
    private val tailcatSharing = TailcatSharing(
        saved = { prefs.getBoolean(preferenceKey(), false) },
        save = { prefs.putBoolean(preferenceKey(), it) },
        active = { server?.tailcatEnabled == true },
        toggle = {
            check(running() == null) { "Sharing is managed by the process that owns this Server" }
            (server ?: started()).shareTailcat(it)
        },
        resetAddress = {
            check(running() == null) { "Sharing is managed by the process that owns this Server" }
            (server ?: started()).resetTailcat()
        },
    )
    override val sharingOptions get() = mapOf(SharingOption.Tailcat to tailcatSharing.takeIf { server != null && running() == null })
    private val mutex = Mutex()
    private var server: JvmServerHost? = null
    private var port = 0
    /** One credential for the app's lifetime, so reconnecting stays the same Device, e.g. with its Computer. */
    private var token: String? = null

    private suspend fun started(): JvmServerHost = mutex.withLock {
        server ?: JvmServerHost(JvmAgentRuntime(), tailcat = Tailcat).also { s ->
            port = s.start("0.0.0.0", System.getenv("KINUVO_EMBEDDED_PORT")?.toInt()?.let(::listOf) ?: JvmServerHost.EMBEDDED_PORTS)
            s.advertise()
            Runtime.getRuntime().addShutdownHook(Thread { s.stop() })
            server = s
            tailcatSharing.restore()
        }
    }

    private fun running() = LocalServer.read(kinuvoHome())?.takeIf { it.alive && it.pid != ProcessHandle.current().pid() }?.let { Endpoint("ws://127.0.0.1:${it.port}/ws", it.token) }

    /** A login service for this home is about to start its Server, which the app joins instead of starting a second one. */
    private suspend fun starting(): Endpoint? {
        if (LoginServiceSettings.read()?.home != kinuvoHome().toString() || ServiceAgent.status() != AgentStatus.Enabled) return null
        return withTimeoutOrNull(20_000) {
            var found = running()
            while (found == null) { delay(500); found = running() }
            found
        }
    }

    override suspend fun endpoint(): Endpoint {
        (running() ?: starting())?.let { return it }
        val server = try { started() } catch (e: LinkageError) {
            throw IllegalStateException("The local Server could not start because the app runtime is incomplete: ${e.message}. Reinstall or rebuild Kinuvo.", e)
        }
        val token = mutex.withLock { token ?: server.auth.sessionToken().also { token = it } }
        return Endpoint("ws://127.0.0.1:$port/ws", token)
    }

}

/** This computer's screen for computer use, offered under This Device's tools like a phone's. */
private class CuaComputerDriver(private val desktop: DesktopComputer = DesktopComputer(File(kinuvoHome().toString()))) : ComputerDriver {
    override val ready get() = desktop.ready
    override val requirement = "Needs cua-driver (github.com/trycua/cua) with Accessibility and Screen Recording: run `cua-driver permissions grant`."
    override suspend fun perform(request: ComputerRequest) = desktop.perform(request)
}

fun main(args: Array<String>) {
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    when (args.firstOrNull()) {
        "--serve" -> exitProcess(serveFromLogin())
        "--service" -> exitProcess(runServiceCommand(args.getOrNull(1)))
    }
    startKinuvo(module {
        single<EmbeddedServer> { HarnessEmbeddedServer }
        single<ComputerDriver> { CuaComputerDriver() }
        single<TailcatForwarder> { Tailcat }
    })
    nucleusApplication(args = args, enableSingleInstance = false) {
        val state = rememberWindowState(width = 1100.dp, height = 760.dp)
        var frame by remember { mutableStateOf<NucleusWindow?>(null) }
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
        DecoratedWindow(onCloseRequest = ::exitApplication, title = "Kinuvo", icon = appIcon(), state = state) {
            LaunchedEffect(nucleusWindow) { frame = nucleusWindow }
            // Content draws under a transparent bar, ChatGPT-style; its own controls take clicks there.
            WindowScaffold(
                titleBar = { Box(Modifier.fillMaxWidth().height(TitleBarHeight).windowDragArea()) },
                titleBarPlacement = TitleBarPlacement.Overlay(passThroughToContent = true),
            ) { _ ->
                val insets = LocalWindowChromeInsets.current.controlsInsets
                val direction = LocalLayoutDirection.current
                val leading = insets.calculateLeftPadding(direction)
                val trailing = insets.calculateRightPadding(direction)
                val fullscreen = this@DecoratedWindow.state.isFullscreen
                val chrome = remember(leading, trailing, fullscreen) { if (fullscreen) null else WindowChrome(TitleBarHeight, leading, trailing) }
                CompositionLocalProvider(LocalWindowChrome provides chrome) { KinuvoApp() }
            }
        }
    }
}

private val TitleBarHeight = 52.dp
