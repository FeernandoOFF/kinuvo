package kinuvo.server.plugin

import kotlinx.io.files.Path

/** Exposes a local port to paired phones through an encrypted peer-to-peer tunnel. */
interface TailcatServing {
    /** Serves [port] to the phones saved in [state]; [name] is what a phone shows while pairing. */
    fun serve(state: Path, port: Int, name: String): TailcatTunnel
    /** Forgets the tunnel identity and every paired phone; the tunnel must not be serving. */
    fun reset(state: Path)
    /** Unpairs [phone] from [state] while the tunnel is not serving. */
    fun revoke(state: Path, phone: String)
}

interface TailcatTunnel : AutoCloseable {
    /** Opens a one-phone pairing window whose reply carries [payload]; [phoneOf] later finds the phone that used [code]. */
    fun invite(code: String, payload: String, ttlSeconds: Int): TailcatInvite
    fun phoneOf(code: String): String?
    fun revoke(phone: String)
}

interface TailcatInvite : AutoCloseable {
    /** The text a phone scans or pastes to pair. */
    val text: String
    /** Epoch seconds. */
    val expiresAt: Long
    /** False once a phone joined or the window expired. */
    val open: Boolean
}
