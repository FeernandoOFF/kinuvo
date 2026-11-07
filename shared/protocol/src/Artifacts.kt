package kinuvo.protocol

import kotlinx.serialization.Serializable

/** A file an Agent made for the user, kept in `~/.kinuvo/artifacts`; only its latest content is kept. Times are epoch ms. */
@Serializable
data class ArtifactInfo(
    val id: String,
    val title: String,
    /** File extension: html, svg, md, csv, json, txt or design. */
    val type: String,
    val agentId: String,
    /** The Session it was last saved from. */
    val sessionId: String? = null,
    val created: Long,
    val updated: Long,
)

@Serializable
data class ArtifactData(val info: ArtifactInfo, val content: String)

/** A user edit: [content] replaces the whole source. */
@Serializable
data class ArtifactSave(val id: String, val content: String)

@Serializable
data class ArtifactRef(val id: String)

/** [info] is null when the Artifact was deleted. */
@Serializable
data class ArtifactChange(val id: String, val info: ArtifactInfo? = null)

/** A `design` Artifact's content: [artboards] laid out left to right. */
@Serializable
data class Design(val artboards: List<Artboard> = emptyList())

/** One page of a [Design]: a self-contained [html] document shown at [width] x [height]. */
@Serializable
data class Artboard(
    val id: String,
    val name: String = id,
    val width: Int = 390,
    val height: Int = 844,
    val html: String,
)
