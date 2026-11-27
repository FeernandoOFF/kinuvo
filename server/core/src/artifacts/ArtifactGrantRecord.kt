package kinuvo.server.artifacts

import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.protocol.artifact.ArtifactNetwork
import kotlinx.serialization.Serializable

/** What the owner granted one Artifact, kept with it: the capabilities, when (epoch ms) and from which Device. */
@Serializable
data class ArtifactGrantRecord(
    val granted: ArtifactCapabilities,
    val at: Long,
    val by: String? = null,
    val byName: String? = null,
)

internal val NONE = ArtifactCapabilities()

/** [this] with every origin normalized and any an Artifact may not reach, or any duplicate or blank tool, dropped. */
internal fun ArtifactCapabilities.cleaned() = copy(
    network = network.mapNotNull(ArtifactNetwork::origin).distinct(),
    tools = tools.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
)

/** What applies: only what is both asked for and granted. */
internal fun effective(requested: ArtifactCapabilities, granted: ArtifactCapabilities) = ArtifactCapabilities(
    network = requested.network.filter { it in granted.network },
    popups = requested.popups && granted.popups,
    storage = requested.storage && granted.storage,
    tools = requested.tools.filter { it in granted.tools },
)

internal operator fun ArtifactCapabilities.plus(other: ArtifactCapabilities) = ArtifactCapabilities(
    network = (network + other.network).distinct(), popups = popups || other.popups, storage = storage || other.storage, tools = (tools + other.tools).distinct(),
)

internal operator fun ArtifactCapabilities.minus(other: ArtifactCapabilities) = ArtifactCapabilities(
    network = network - other.network.toSet(), popups = popups && !other.popups, storage = storage && !other.storage, tools = tools - other.tools.toSet(),
)
