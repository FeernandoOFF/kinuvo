package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import kinuvo.client.KinuvoClient
import kinuvo.harness.cua.DesktopComputer
import kinuvo.harness.server.HarnessServer
import kinuvo.protocol.DeviceToolKind
import kotlinx.coroutines.withTimeout

/** Offers the CLI's desktop as a Device through the same protocol as the desktop app. */
internal class CliComputer(server: HarnessServer, host: String, port: Int, private val desktop: DesktopComputer = DesktopComputer()) : AutoCloseable {
    init {
        System.setProperty("java.awt.headless", "true")
        if (!desktop.ready) throw CliktError("Computer use needs cua-driver on PATH, in ~/.local/bin or /Applications/CuaDriver.app. Install it and run `cua-driver permissions grant`.")
    }

    private val address = when (host) { "0.0.0.0" -> "127.0.0.1"; "::" -> "::1"; else -> host }
    private val client = KinuvoClient("ws://${if (':' in address) "[$address]" else address}:$port/ws", "Server desktop", server.auth.sessionToken(),
        deviceTools = { setOf(DeviceToolKind.Computer) }, computer = desktop::perform)

    suspend fun connect() {
        client.setActive(false)
        withTimeout(10_000) { client.connect() }
    }

    override fun close() { client.close(); desktop.close() }
}
