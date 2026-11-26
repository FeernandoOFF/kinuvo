package kinuvo.server.execution

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.device.Links
import kinuvo.protocol.device.TailcatPayload
import kinuvo.protocol.execution.ExposureInvite
import kinuvo.server.devices.PeerRegistry
import kinuvo.server.plugin.TailcatInvite
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Private-tunnel invites for link codes: each opens a one-phone window, and the phone that uses it is bound to the grant it came from. */
class RemoteInvites(private val links: RemoteLinks, private val peers: PeerRegistry) {
    private class Window(val code: String, val invite: TailcatInvite)
    private val lock = Lock()
    private val windows = mutableMapOf<String, Window>()

    val available get() = peers.tailcatActive

    /** Opens a window for the grant [id]; its payload carries the grant's link code. Null when the tunnel is off. */
    fun invite(id: String): ExposureInvite? = lock.withLock {
        val grant = requireNotNull(links.grant(id)) { "Link code not found" }
        windows.remove(id)?.invite?.let { runCatching { it.close() } }
        val code = Uuid.random().toString()
        val payload = ProtocolJson.encodeToString(TailcatPayload.serializer(), TailcatPayload(link = grant.token))
        val invite = peers.tailcatInvite(code, payload, TTL_SECONDS, links.phones()) ?: return null
        windows[id] = Window(code, invite)
        ExposureInvite(invite.text, Links.tailcatLink(invite.text), Instant.fromEpochSeconds(invite.expiresAt).toString())
    }

    /** Binds the tunnel phone [peer] to the grant [token] belongs to, when [peer] is the one that used its invite. */
    fun connected(token: String, peer: String?) {
        if (peer == null) return
        lock.withLock {
            val grant = links.grantOf(token) ?: return
            if (grant.phone != null) return
            val window = windows[grant.id] ?: return
            if (peers.tailcatPhoneOf(window.code) != peer) return
            links.bind(grant.id, peer)
            windows.remove(grant.id)?.invite?.let { runCatching { it.close() } }
        }
    }

    /** Closes the window of grant [id] and unpairs the phone bound to it. */
    fun forget(id: String, phone: String?) {
        lock.withLock { windows.remove(id)?.invite?.let { runCatching { it.close() } } }
        phone?.let(peers::tailcatRevoke)
    }

    private companion object { const val TTL_SECONDS = 600 }
}
