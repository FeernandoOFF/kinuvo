package kinuvo.server.plugin

import kotlinx.io.files.Path

/** Exposes a local port through an encrypted peer-to-peer tunnel at a secret Tailcat address. */
interface TailcatServing {
    fun serve(state: Path, port: Int): TailcatTunnel
    fun reset(state: Path)
}

interface TailcatTunnel : AutoCloseable {
    val address: String
}
