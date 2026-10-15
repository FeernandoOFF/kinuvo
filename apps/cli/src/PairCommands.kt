package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kinuvo.protocol.*
import kotlin.time.Clock
import kotlin.time.Instant

internal class PairCommand : ServerCommand("pair", "Show a pairing code and QR code for another Device; scan it with the phone's camera.") {
    private val management by option("--manage", help = "Grant management access, including configuration, pairing and Approvals").flag()
    private val refresh by option("--refresh", help = "Make a new code, invalidating the current one").flag()
    private val host by option("--host", help = "Address to put in the QR code (default: every address)")

    override suspend fun run() {
        val pairing = session.call(running = true) { client.call(Api.Pairing, PairingParams(refresh, management)) }
        val hosts = pairing.addresses.ifEmpty { listOf("127.0.0.1") }
        val minutes = (Instant.parse(pairing.expiresAt) - Clock.System.now()).inWholeMinutes.coerceAtLeast(0) + 1
        echo(if (pairing.management) "Access: management" else "Access: ordinary Device")
        echo("Pairing code: ${pairing.code} (valid for ~$minutes min, one Device)")
        val bases = host?.let { listOf(if (it.startsWith("https://")) it.removeSuffix("/") else "http://${if (':' in it && !it.startsWith('[')) "[$it]" else it}:${pairing.port}") }
            ?: pairing.publicUrl?.let(::listOf) ?: hosts.map { "http://${if (':' in it && !it.startsWith('[')) "[$it]" else it}:${pairing.port}" }
        bases.forEach { base ->
            echo("App link:     ${Links.pair(base, pairing.code)}")
            echo("Web link:     ${Links.webPair(base, pairing.code)}")
            echo(qr(Links.pair(base, pairing.code)))
        }
    }
}

internal class DeviceCommands : Group("devices", "List or revoke the Devices paired with the Server.", ListDevices(), RevokeDevice()) {
    private val session by requireObject<Session>()
    override val invokeWithoutSubcommand = true
    override suspend fun run() {
        if (currentContext.invokedSubcommand == null) echo(deviceTable(session))
    }
}

private class ListDevices : ServerCommand("list", "List paired Devices.") {
    override suspend fun run() {
        echo(deviceTable(session))
    }
}

private class RevokeDevice : ServerCommand("revoke", "Unpair a Device; it has to pair again to connect.") {
    private val device by argument("DEVICE", help = "ID or name")

    override suspend fun run() {
        val name = session.call(running = true) {
            val all = client.call(Api.PairedDevices, Unit)
            val target = all.firstOrNull { it.id == device } ?: all.filter { it.name == device }.let {
                it.singleOrNull() ?: throw CliktError(if (it.isEmpty()) "No paired Device '$device'" else "Several Devices are named '$device'; use its ID")
            }
            client.call(Api.RevokeDevice, DeviceRef(target.id))
            target.name
        }
        echo("Revoked $name")
    }
}

private suspend fun deviceTable(session: Session): String {
    val devices = session.call(running = true) { client.call(Api.PairedDevices, Unit) }
    return table(listOf("ID", "NAME", "PAIRED", "LAST SEEN"), devices.map {
        listOf(it.id, it.name, it.pairedAt.take(16).replace('T', ' '), if (it.connected) "connected" else it.lastSeen?.take(16)?.replace('T', ' ') ?: "-")
    })
}

/** [text] as a QR code drawn with half blocks, two modules per line, light on a dark terminal. */
internal fun qr(text: String): String {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    return (0 until m.height step 2).joinToString("\n") { y ->
        (0 until m.width).joinToString("") { x ->
            val top = !m.get(x, y)
            val bottom = y + 1 >= m.height || !m.get(x, y + 1)
            when {
                top && bottom -> "█"
                top -> "▀"
                bottom -> "▄"
                else -> " "
            }
        }
    }
}
