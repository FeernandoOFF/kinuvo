package kinuvo.server.jvm

import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.server.NetworkSecurity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private val log = KotlinLogging.logger("kinuvo.serve")

/** A problem the owner can fix, e.g. Tailscale not connected; shown without a stack trace. */
class ServeException(message: String) : RuntimeException(message)

/** What a headless Server listens on and how it is reached from outside. */
@Serializable
data class ServeOptions(
    val host: String = "127.0.0.1",
    val port: Int = HarnessServer.DEFAULT_PORT,
    val publicUrl: String? = null,
    val trustedProxy: String? = null,
    val tailscale: Boolean = false,
    val tailscalePort: Int = 443,
    val behindProxy: Boolean = false,
)

private fun env(name: String) = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

/** The first valid HTTPS origin of Coolify's `COOLIFY_URL` (comma-separated, may carry a path), or null. */
internal fun coolifyOrigin(value: String?): String? = value?.split(',')?.firstNotNullOfOrNull { candidate ->
    runCatching {
        val uri = java.net.URI(candidate.trim())
        NetworkSecurity("${uri.scheme}://${uri.rawAuthority}").publicUrl.takeIf { uri.scheme == "https" }
    }.getOrNull()
}

/** Public URL by precedence: option, `KINUVO_PUBLIC_URL`, then (behind a proxy) `COOLIFY_URL`. */
internal fun publicUrl(option: String?, behindProxy: Boolean, env: (String) -> String? = ::env): String? =
    option?.trim()?.takeIf { it.isNotEmpty() } ?: env("KINUVO_PUBLIC_URL") ?: if (behindProxy) coolifyOrigin(env("COOLIFY_URL")) else null

/** Runs the Server until cancelled; [extend] adds to it once it listens and is closed when it stops. */
suspend fun serve(options: ServeOptions, extend: suspend (server: HarnessServer, host: String, port: Int) -> AutoCloseable? = { _, _, _ -> null }) {
    require(!options.tailscale || options.publicUrl == null) { "Use --tailscale or --public-url, not both" }
    val route = if (options.tailscale) {
        Tailscale.requireLoopback(options.host)
        Tailscale().prepare(options.port, options.tailscalePort)
    } else null
    val bind = if (options.tailscale) "127.0.0.1" else options.host
    val behindProxy = options.behindProxy || env("KINUVO_BEHIND_PROXY")?.lowercase() in listOf("1", "true")
    val trusted = (options.trustedProxy?.trim()?.takeIf { it.isNotEmpty() } ?: env("KINUVO_TRUSTED_PROXIES"))?.split(',')?.map(String::trim)?.toSet()
    val network = NetworkSecurity(route?.url ?: publicUrl(options.publicUrl, behindProxy), trusted, behindProxy)
    val server = HarnessServer(Harness(), network = network)
    var extension: AutoCloseable? = null
    var ready = false
    val shutdown = Thread { try { server.stop() } finally { extension?.close() } }
    try {
        val bound = server.start(bind, options.port)
        route?.enable()
        extension = extend(server, bind, bound)
        server.advertise()
        log.info { "Server listening at $bind:$bound${network.publicUrl?.let { "; HTTPS address $it" }.orEmpty()}" }
        if (route != null) log.info { "Tailscale HTTPS: ${route.url} (private to your tailnet; the Serve route persists until disabled)" }
        log.info { "Pairing is closed. Run `kinuvo pair --manage` for a management Device or `kinuvo pair` for ordinary access." }
        Runtime.getRuntime().addShutdownHook(shutdown)
        ready = true
        awaitCancellation()
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        try { server.stop() } finally {
            try { extension?.close() } finally {
                if (!ready) withContext(NonCancellable) {
                    runCatching { route?.rollback() }.onFailure { log.warn(it) { "Could not remove the new Tailscale route; check `tailscale serve status`." } }
                }
            }
        }
    }
}
