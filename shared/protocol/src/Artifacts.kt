package kinuvo.protocol

import kotlinx.serialization.Serializable

/** A file an Agent made for the user, kept in `~/.kinuvo/artifacts`; only its latest content is kept. Times are epoch ms. */
@Serializable
data class ArtifactInfo(
    val id: String,
    val title: String,
    /** File extension: html, svg, md, csv, json or txt. */
    val type: String,
    val agentId: String,
    /** The Session it was last saved from. */
    val sessionId: String? = null,
    val created: Long,
    val updated: Long,
    val roomSessionId: String? = null,
)

@Serializable
data class ArtifactData(val info: ArtifactInfo, val content: String)

@Serializable
data class ArtifactRef(val id: String)

/** [info] is null when the Artifact was deleted. */
@Serializable
data class ArtifactChange(val id: String, val info: ArtifactInfo? = null)
