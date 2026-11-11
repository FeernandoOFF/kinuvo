package kinuvo.protocol

import kotlinx.serialization.Serializable

/** Optional provider-reported usage limits. */
@Serializable
data class Usage(
    val provider: String,
    val windows: List<Window> = emptyList(),
    val plan: String? = null,
) {
    @Serializable
    data class Window(val name: String, val usedPercent: Double, val windowMinutes: Int, val resetsAtEpochSeconds: Long? = null)
}
