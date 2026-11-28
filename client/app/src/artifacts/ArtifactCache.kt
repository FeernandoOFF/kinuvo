package kinuvo.app.artifacts

import kinuvo.app.connection.ServerBound
import kinuvo.protocol.artifact.ArtifactData
import kinuvo.protocol.artifact.ArtifactInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** What this Device last saw of a Server's Artifacts: the list, and the most recently opened ones (newest first) with their content. */
@Serializable
data class CachedArtifacts(val all: List<ArtifactInfo>? = null, val recent: List<ArtifactData> = emptyList()) {
    /** What the Device's snapshot keeps: large Artifacts, e.g. decks with embedded video, stay in memory only. */
    val saved get() = copy(recent = recent.filter { it.content.length <= MAX_SAVED_CHARS })
}

private const val MAX_SAVED_CHARS = 1_000_000

/**
 * Lets a reopened Artifact show at once while the Server is asked whether it changed. Keeps at most [MAX_RECENT]
 * Artifacts and [MAX_CHARS] characters of content; the Device's snapshot saves the smaller ones for the next launch.
 */
class ArtifactCache : ServerBound {
    val state: StateFlow<CachedArtifacts>
        field = MutableStateFlow(CachedArtifacts())

    fun get(id: String): ArtifactData? = state.value.recent.firstOrNull { it.info.id == id }

    fun list(all: List<ArtifactInfo>) = state.update { c -> c.copy(all = all, recent = c.recent.filter { r -> all.any { it.id == r.info.id } }) }

    fun put(data: ArtifactData) = state.update { c ->
        var chars = 0
        val recent = (listOf(data) + c.recent.filter { it.info.id != data.info.id }).take(MAX_RECENT)
            .takeWhile { chars += it.content.length; chars <= MAX_CHARS || it === data }
        c.copy(recent = recent)
    }

    fun restore(cached: CachedArtifacts) { state.value = cached }

    override fun detach() { state.value = CachedArtifacts() }

    private companion object {
        const val MAX_RECENT = 10
        const val MAX_CHARS = 8_000_000
    }
}
