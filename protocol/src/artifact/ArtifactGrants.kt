package kinuvo.protocol.artifact

import kotlinx.serialization.Serializable

/**
 * What the owner allows one Artifact, next to what its manifest asks for. [requested] is the manifest's `capabilities`; [granted] is what the owner
 * allowed, which may name things no longer requested; [effective] is what clients enforce: [requested] and [granted] together. Nothing is ever granted
 * for the owner, so a request that is new or changed shows as [pending] until they allow it. [at] and [by] say when and from which Device.
 */
@Serializable
data class ArtifactPermissions(
    val id: String,
    val requested: ArtifactCapabilities = ArtifactCapabilities(),
    val granted: ArtifactCapabilities = ArtifactCapabilities(),
    val effective: ArtifactCapabilities = ArtifactCapabilities(),
    val at: Long? = null,
    val by: String? = null,
    val byName: String? = null,
) {
    /** The manifest asks for something the owner has not allowed. */
    val pending get() = requested.network.any { it !in effective.network } || (requested.popups && !effective.popups) ||
        (requested.storage && !effective.storage) || requested.tools.any { it !in effective.tools }

    /** The manifest asks for something at all. */
    val asking get() = requested != ArtifactCapabilities()

    /** Every request is allowed, and there is at least one. */
    val everything get() = asking && !pending
}

/**
 * Changes what the owner grants [id], for management Devices only. [all] true grants everything the manifest asks for now, false takes every grant back;
 * then [grant] adds to and [revoke] removes from what is granted. Granting what the manifest does not ask for fails.
 */
@Serializable
data class ArtifactGrantChange(
    val id: String,
    val all: Boolean? = null,
    val grant: ArtifactCapabilities = ArtifactCapabilities(),
    val revoke: ArtifactCapabilities = ArtifactCapabilities(),
)

/** The origins an Artifact may ask to reach: only encrypted ones, `https://host[:port]` or `wss://host[:port]`, with a wildcard only as a leading `*.` label. */
object ArtifactNetwork {
    const val MAX_ORIGINS = 32

    /** [text] as a normalized origin (lower case, no path or trailing slash), or null when an Artifact may not ask for it. */
    fun origin(text: String): String? {
        val raw = text.trim().removeSuffix("/")
        val scheme = SCHEMES.firstOrNull { raw.startsWith("$it://", ignoreCase = true) } ?: return null
        val authority = raw.substring(scheme.length + 3).lowercase()
        if (authority.isEmpty() || authority.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '\\' || it.isWhitespace() || it == '[' || it == ']' || it == '%' }) return null
        val host = authority.substringBefore(':')
        val port = if (':' in authority) authority.substringAfter(':') else null
        if (port != null && (port.isEmpty() || port.length > 5 || port.any { !it.isDigit() } || port.toInt() !in 1..65535)) return null
        val labels = host.removePrefix("*.").split('.')
        if (labels.size < 2) return null
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith("-") || it.endsWith("-") || it.any { c -> !(c in 'a'..'z' || c in '0'..'9' || c == '-') } }) return null
        if (loopback(labels)) return null
        return "$scheme://$authority"
    }

    /** [origins] normalized, with a refusal that names the first one an Artifact may not ask for. */
    fun checked(origins: List<String>): List<String> {
        require(origins.size <= MAX_ORIGINS) { "An artifact may ask for at most $MAX_ORIGINS network origins" }
        return origins.map { origin(it) ?: throw IllegalArgumentException("'$it' is not an origin an artifact may reach: use https://host[:port] or wss://host[:port], with * only as a leading *. label and no localhost or loopback") }.distinct()
    }

    private val SCHEMES = listOf("https", "wss")

    private fun loopback(labels: List<String>) = labels.last() == "localhost" || (labels.size == 4 && labels.all { l -> l.isNotEmpty() && l.all(Char::isDigit) } && (labels[0] == "127" || labels[0] == "0"))
}
