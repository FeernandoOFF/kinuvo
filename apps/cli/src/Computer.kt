package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import kinuvo.client.KinuvoClient
import java.io.File
import kinuvo.plugins.cua.DesktopComputer
import kinuvo.server.jvm.kinuvoHome
import kinuvo.server.jvm.JvmServerHost
import kinuvo.protocol.device.DeviceToolKind
import kotlinx.coroutines.withTimeout

/** Offers the CLI's desktop as a Device through the same protocol as the desktop app. */
internal class CliComputer(server: JvmServerHost, host: String, port: Int, private val desktop: DesktopComputer = DesktopComputer(File(kinuvoHome().toString()))) : AutoCloseable {
    init {
        System.setProperty("java.awt.headless", "true")
        if (!desktop.ready) throw CliktError("Computer use needs an installed cua-driver or a supported desktop platform for automatic download.")
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
