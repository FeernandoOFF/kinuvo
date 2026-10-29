package kinuvo.harness.server.execution

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kinuvo.protocol.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Private Server storage. Never serialize this value into a public catalog or event. */
@Serializable
data class RemoteSecrets(val links: List<SaveRemoteLink> = emptyList(), val grants: List<StoredExposure> = emptyList(), val sessions: List<ExposedSession> = emptyList())

@Serializable
data class ExposedSession(val token: String, val agentId: String, val sessionId: String)

@Serializable
data class StoredExposure(val id: String, val token: String, val agentIds: Set<String>)

class RemoteLinks(initial: RemoteSecrets = RemoteSecrets(), private val persist: (RemoteSecrets) -> Unit = {}) {
    private val lock = Lock()
    private var secrets = initial

    fun link(id: String): SaveRemoteLink = lock.withLock { requireNotNull(secrets.links.firstOrNull { it.id == id }) { "Remote Server connection is not configured" } }
    fun save(link: SaveRemoteLink) = lock.withLock {
        require(link.id.isNotBlank() && link.token.isNotBlank())
        requireSecureTransport(link.url, allowCleartext = true)
        val next = secrets.copy(links = secrets.links.filterNot { it.id == link.id } + link)
        persist(next)
        secrets = next
    }
    fun grant(grant: ExposureGrant): String = lock.withLock {
        require(grant.id.isNotBlank() && grant.agentIds.isNotEmpty())
        val token = Uuid.random().toString() + Uuid.random().toString()
        val next = secrets.copy(grants = secrets.grants.filterNot { it.id == grant.id } + StoredExposure(grant.id, token, grant.agentIds))
        persist(next)
        secrets = next
        token
    }
    fun grants(): List<ExposureGrant> = lock.withLock { secrets.grants.map { ExposureGrant(it.id, it.agentIds) } }
    fun revoke(id: String) = lock.withLock {
        val next = secrets.copy(grants = secrets.grants.filterNot { it.id == id })
        persist(next)
        secrets = next
    }
    fun claim(token: String, agentId: String, sessionId: String) = lock.withLock {
        require(secrets.grants.any { it.token == token && agentId in it.agentIds }) { "Remote access revoked" }
        require(secrets.sessions.none { it.sessionId == sessionId }) { "Session is already owned" }
        val next = secrets.copy(sessions = secrets.sessions + ExposedSession(token, agentId, sessionId))
        persist(next)
        secrets = next
    }
    fun owns(token: String, agentId: String, sessionId: String) = lock.withLock {
        secrets.sessions.any { it.token == token && it.agentId == agentId && it.sessionId == sessionId }
    }
    fun exposure(token: String): Set<String>? = lock.withLock { secrets.grants.firstOrNull { it.token == token }?.agentIds }
}
