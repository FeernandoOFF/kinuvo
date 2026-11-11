package kinuvo.server

import kinuvo.server.plugin.HarnessPlugin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kinuvo.protocol.agent.AgentId

/** Owns installed plugins and network resources; shutdown is complete even when a closer fails or is cancelled. */
class ServerResources(val plugins: List<HarnessPlugin>, private val closeNetwork: () -> Unit = {}) {
    private val lock = Mutex()
    private var closed = false

    suspend fun release(agentId: AgentId) = lock.withLock {
        if (closed) return@withLock
        var failure: Throwable? = null
        plugins.distinct().forEach { plugin ->
            try { plugin.release(agentId) } catch (e: Throwable) { if (failure == null) failure = e else failure.addSuppressed(e) }
        }
        failure?.let { throw it }
    }

    suspend fun close() = withContext(NonCancellable) { lock.withLock {
        if (closed) return@withLock
        closed = true
        plugins.distinct().forEach { runCatching { it.close() } }
        closeNetwork()
    } }
}
