package kinuvo.server.jvm

import kinuvo.server.DeviceAuth
import kinuvo.server.plugin.TailcatServing
import kinuvo.server.plugin.TailcatTunnel
import kotlinx.io.files.Path

internal class TailcatExposure(
    private val auth: DeviceAuth,
    private val native: TailcatServing,
) : AutoCloseable {
    private var tunnel: TailcatTunnel? = null
    @Synchronized fun start(state: Path, port: Int) {
        check(tunnel == null)
        val started = native.serve(state, port)
        tunnel = started
        auth.tailcatAddress = started.address
    }
    @Synchronized override fun close() {
        auth.tailcatAddress = null
        val current = tunnel
        tunnel = null
        current?.close()
    }
}
