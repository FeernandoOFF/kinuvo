package kinuvo.server.jvm

import java.io.File
import kinuvo.server.DeviceAuth
import kinuvo.transport.tailcat.Tailcat

internal class TailcatExposure(
    private val auth: DeviceAuth,
    private val native: Tailcat,
) : AutoCloseable {
    private var tunnel: Tailcat.Tunnel? = null
    @Synchronized fun start(state: File, port: Int) {
        check(tunnel == null)
        val started = native.serve(state, port)
        tunnel = started
        auth.tailcatAddress = requireNotNull(started.address)
    }
    @Synchronized override fun close() {
        auth.tailcatAddress = null
        val current = tunnel
        tunnel = null
        current?.close()
    }
}
