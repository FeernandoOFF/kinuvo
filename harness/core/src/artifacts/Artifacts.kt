package kinuvo.harness.core.artifacts

import kinuvo.protocol.ArtifactChange
import kinuvo.protocol.ArtifactData
import kinuvo.protocol.ArtifactInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.random.Random
import kotlin.time.Clock

/** Where Artifacts are kept: each one's latest content and its [ArtifactInfo]. */
interface ArtifactRepository {
    suspend fun list(): List<ArtifactInfo>
    suspend fun get(id: String): ArtifactData?
    suspend fun save(info: ArtifactInfo, content: String)
    suspend fun delete(id: String): Boolean
}

/** The Artifacts every Agent shares; saving one replaces its content. Reads take the lock too, so they never see half a save. */
@OptIn(ExperimentalAtomicApi::class)
class Artifacts(private val repo: ArtifactRepository, private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val lock = Mutex()
    private val listeners = AtomicReference(emptyList<(ArtifactChange) -> Unit>())

    fun onChange(listener: (ArtifactChange) -> Unit) = listeners.update { it + listener }

    suspend fun list(): List<ArtifactInfo> = lock.withLock { repo.list() }.sortedByDescending { it.updated }

    suspend fun get(id: String): ArtifactData = lock.withLock { find(id) }

    private suspend fun find(id: String) = repo.get(id) ?: error("No artifact '$id'")

    suspend fun create(agentId: String, sessionId: String?, title: String, type: String, content: String): ArtifactInfo = lock.withLock {
        require(title.isNotBlank()) { "Give the artifact a title" }
        val taken = repo.list().map { it.id }.toSet()
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "artifact" }
        val id = generateSequence { "$slug-${Random.nextInt(0x10000).toString(16).padStart(4, '0')}" }.first { it !in taken }
        val now = clock()
        ArtifactInfo(id, title.trim(), checkedType(type), agentId, sessionId, now, now).also { save(it, content) }
    }

    /** Replaces [id]'s content with what [transform] makes of it, and its title when [title] is set. */
    suspend fun update(id: String, agentId: String, sessionId: String?, title: String? = null, transform: (String) -> String): ArtifactInfo = lock.withLock {
        val old = find(id)
        val info = old.info.copy(title = title?.trim()?.ifEmpty { null } ?: old.info.title, agentId = agentId, sessionId = sessionId, updated = clock())
        info.also { save(it, transform(old.content)) }
    }

    suspend fun delete(id: String) = lock.withLock {
        check(repo.delete(id)) { "No artifact '$id'" }
        notify(ArtifactChange(id))
    }

    private suspend fun save(info: ArtifactInfo, content: String) {
        require(content.length <= MAX_CHARS) { "Artifacts are limited to ${MAX_CHARS / 1_000_000} MB" }
        repo.save(info, content)
        notify(ArtifactChange(info.id, info))
    }

    private fun notify(change: ArtifactChange) = listeners.load().forEach { runCatching { it(change) } }

    companion object {
        val TYPES = listOf("html", "svg", "md", "csv", "json", "txt")
        const val MAX_CHARS = 5_000_000

        fun checkedType(type: String) = type.lowercase().removePrefix(".").let { t ->
            when (t) {
                "htm" -> "html"
                "markdown" -> "md"
                else -> t.also { require(it in TYPES) { "Type must be one of ${TYPES.joinToString()}" } }
            }
        }
    }
}
