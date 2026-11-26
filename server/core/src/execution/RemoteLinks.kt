package kinuvo.server.execution

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kinuvo.protocol.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Private Server storage. Never serialize this value into a public catalog or event. */
@Serializable
data class RemoteSecrets(val links: List<SaveRemoteLink> = emptyList(), val tunnels: List<RemoteTunnel> = emptyList(), val grants: List<StoredExposure> = emptyList(), val sessions: List<ExposedSession> = emptyList())

@Serializable
data class ExposedSession(val token: String, val agentId: AgentId, val sessionId: SessionId)

/** The secret that reopens the private tunnel to the Server linked as [id]; its address in [SaveRemoteLink.url] is only an opaque key. */
@Serializable
data class RemoteTunnel(val id: String, val link: String)

/** [phone] is the tunnel phone key that linked through this grant's invite, once it has connected. */
@Serializable
data class StoredExposure(val id: String, val token: String, val agentIds: Set<AgentId>, val phone: String? = null)

const val TUNNEL_PREFIX = "tailcat:"

class RemoteLinks(initial: RemoteSecrets = RemoteSecrets(), private val persist: (RemoteSecrets) -> Unit = {}) {
    private val lock = Lock()
    private var secrets = initial

    fun link(id: String): SaveRemoteLink = lock.withLock { requireNotNull(secrets.links.firstOrNull { it.id == id }) { "Remote Server connection is not configured" } }
    fun save(link: SaveRemoteLink) = lock.withLock {
        require(link.id.isNotBlank() && link.token.isNotBlank())
        requireSecureTransport(link.url, allowCleartext = true)
        val next = secrets.copy(links = secrets.links.filterNot { it.id == link.id } + link, tunnels = secrets.tunnels.filterNot { it.id == link.id })
        persist(next)
        secrets = next
    }
    /** Links the Server reached through the tunnel secret [tunnel] by its exposure [token]; the key stays private to this Server. */
    fun saveTunnel(id: String, tunnel: String, token: String): SaveRemoteLink = lock.withLock {
        require(id.isNotBlank() && token.isNotBlank() && tunnel.isNotBlank())
        val link = SaveRemoteLink(id, "$TUNNEL_PREFIX${Uuid.random()}", token)
        val next = secrets.copy(links = secrets.links.filterNot { it.id == id } + link, tunnels = secrets.tunnels.filterNot { it.id == id } + RemoteTunnel(id, tunnel))
        persist(next)
        secrets = next
        link
    }
    fun tunnel(id: String): String? = lock.withLock { secrets.tunnels.firstOrNull { it.id == id }?.link }
    fun grant(grant: ExposureGrant): String = lock.withLock {
        require(grant.id.isNotBlank() && grant.agentIds.isNotEmpty())
        val token = Uuid.random().toString() + Uuid.random().toString()
        val next = secrets.copy(grants = secrets.grants.filterNot { it.id == grant.id } + StoredExposure(grant.id, token, grant.agentIds))
        persist(next)
        secrets = next
        token
    }
    fun grant(id: String): StoredExposure? = lock.withLock { secrets.grants.firstOrNull { it.id == id } }
    fun grantOf(token: String): StoredExposure? = lock.withLock { secrets.grants.firstOrNull { it.token == token } }
    fun bind(id: String, phone: String) = lock.withLock {
        val next = secrets.copy(grants = secrets.grants.map { if (it.id == id) it.copy(phone = phone) else it })
        persist(next)
        secrets = next
    }
    fun phones(): Set<String> = lock.withLock { secrets.grants.mapNotNull { it.phone }.toSet() }
    fun grants(): List<ExposureGrant> = lock.withLock { secrets.grants.map { ExposureGrant(it.id, it.agentIds) } }
    fun revoke(id: String) = lock.withLock {
        val next = secrets.copy(grants = secrets.grants.filterNot { it.id == id })
        persist(next)
        secrets = next
    }
    fun claim(token: String, agentId: AgentId, sessionId: SessionId) = lock.withLock {
        require(secrets.grants.any { it.token == token && agentId in it.agentIds }) { "Remote access revoked" }
        require(secrets.sessions.none { it.sessionId == sessionId }) { "Session is already owned" }
        val next = secrets.copy(sessions = secrets.sessions + ExposedSession(token, agentId, sessionId))
        persist(next)
        secrets = next
    }
    fun owns(token: String, agentId: AgentId, sessionId: SessionId) = lock.withLock {
        secrets.sessions.any { it.token == token && it.agentId == agentId && it.sessionId == sessionId }
    }
    fun exposure(token: String): Set<AgentId>? = lock.withLock { secrets.grants.firstOrNull { it.token == token }?.agentIds }
}
