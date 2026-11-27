package kinuvo.server.artifacts

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kinuvo.protocol.artifact.ArtifactState
import kotlinx.serialization.json.JsonElement

/**
 * The shared state of Artifact pages, kept in memory: one small JSON value per Artifact, passed to every connection watching it
 * except the one that set it. Nothing is persisted, so a restart forgets it. A [subscriber] is any key naming a connection.
 */
class ArtifactStates {
    private val lock = Lock()
    private val states = HashMap<String, JsonElement>()
    private val watchers = HashMap<String, LinkedHashMap<Any, (ArtifactState) -> Unit>>()

    fun get(id: String): JsonElement? = lock.withLock { states[id] }

    /** Sends [subscriber]'s [deliver] each later change to [id], and returns the latest state. */
    fun watch(id: String, subscriber: Any, deliver: (ArtifactState) -> Unit): JsonElement? = lock.withLock {
        watchers.getOrPut(id) { LinkedHashMap() }[subscriber] = deliver
        states[id]
    }

    fun unwatch(id: String, subscriber: Any) = lock.withLock { remove(id, subscriber) }

    fun disconnect(subscriber: Any) = lock.withLock { watchers.keys.toList().forEach { remove(it, subscriber) } }

    fun watching(id: String, subscriber: Any) = lock.withLock { watchers[id]?.containsKey(subscriber) == true }

    fun watchers(id: String) = lock.withLock { watchers[id]?.size ?: 0 }

    /** Replaces [id]'s state, and passes it on to every other watcher. Delivery happens outside the lock, as a failed one may disconnect and unwatch. */
    fun set(id: String, subscriber: Any, state: JsonElement) {
        require(state.toString().length <= MAX_CHARS) { "Page state is limited to ${MAX_CHARS / 1000} KB" }
        val targets = lock.withLock {
            states[id] = state
            watchers[id].orEmpty().filterKeys { it !== subscriber }.values.toList()
        }
        val change = ArtifactState(id, state)
        targets.forEach { runCatching { it(change) } }
    }

    fun clear(id: String) = lock.withLock { states.remove(id); watchers.remove(id) }

    private fun remove(id: String, subscriber: Any) {
        val ids = watchers[id] ?: return
        ids.remove(subscriber)
        if (ids.isEmpty()) watchers.remove(id)
    }

    companion object {
        const val MAX_CHARS = 16_000
    }
}
