package kinuvo.protocol.artifact

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The shared state of an Artifact page: a small JSON value the Server keeps in memory and passes to every Device watching it. [state] is null before any Device set it. */
@Serializable
data class ArtifactState(val id: String, val state: JsonElement? = null)

/** Replaces the state of [id], which the caller must be watching. */
@Serializable
data class ArtifactStateSet(val id: String, val state: JsonElement)
