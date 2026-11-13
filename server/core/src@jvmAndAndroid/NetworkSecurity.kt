package kinuvo.server

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.InetAddress

private val log = KotlinLogging.logger("kinuvo.security")

/**
 * Origin and proxy policy. [trustedProxies] are literal IPs or CIDR ranges; by default loopback, or in [behindProxy] mode
 * (a reverse proxy in another container) loopback plus private ranges.
 */
class NetworkSecurity(publicUrl: String? = null, trustedProxies: Set<String>? = null, val behindProxy: Boolean = false) {
    private val trusted = (trustedProxies ?: if (behindProxy) PROXY_DEFAULTS else LOOPBACK_DEFAULTS).map(::range)
    /** The HTTPS origin of the latest browser request a trusted proxy forwarded, in [behindProxy] mode without a public URL; only advertised, never trusted. */
    @Volatile var learnedOrigin: String? = null; private set
    val publicUrl = publicUrl?.let {
        val uri = URI(it)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535) && uri.path in listOf("", "/")) {
            "Public URL must be an HTTPS origin, such as https://kinuvo.example.com"
        }
        it.removeSuffix("/")
    }
    private val publicOrigin = this.publicUrl?.let(::origin)

    fun acceptsOrigin(value: String?, host: String, localHosts: Set<String>): Boolean {
        if (value == null) return true
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.path.isNotEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        return if (publicOrigin != null) origin(value) == publicOrigin
        else uri.scheme == "http" && uri.host?.removeSurrounding("[", "]") in localHosts && uri.rawAuthority == host
    }

    /** [acceptsOrigin], or, without a public URL, an `https://` origin equal to the authority a trusted proxy forwarded. */
    fun acceptsBrowser(value: String?, host: String, localHosts: Set<String>, peer: String, forwardedProto: String?, forwardedHost: String?): Boolean {
        if (acceptsOrigin(value, host, localHosts)) return true
        if (!behindProxy || publicOrigin != null || value == null || !secureProxy(peer, forwardedProto)) return false
        val authority = (forwardedHost?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() } ?: host).lowercase()
        if (authority.isEmpty() || authority.any { it in "/@?#\\" || it.isWhitespace() }) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.path.isNotEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        val expected = origin("https://$authority") ?: return false
        if (origin(value) != expected) return false
        learnedOrigin = "https://$authority".removeSuffix(":443")
        return true
    }

    fun source(peer: String, forwardedFor: String?): String {
        if (!trusts(peer) || forwardedFor == null) return literalAddress(peer)
        val source = forwardedFor.substringAfterLast(',').trim()
        return literalAddress(source)
    }
    fun secureProxy(peer: String, forwardedProto: String?) = trusts(peer) && forwardedProto == "https"

    private fun trusts(peer: String): Boolean {
        val address = InetAddress.getByName(literalAddress(peer)).address
        return trusted.any { (network, prefix) -> network.size == address.size && (0 until prefix).all { bit -> (network[bit / 8].toInt() xor address[bit / 8].toInt()) and (0x80 shr bit % 8) == 0 } }
    }

    private fun range(value: String): Pair<ByteArray, Int> {
        val address = InetAddress.getByName(literalAddress(value.substringBefore('/'))).address
        if ('/' !in value) return address to address.size * 8
        val bits = value.substringAfter('/').takeIf { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }?.toInt()
        require(bits != null && bits <= address.size * 8) { "Invalid proxy range" }
        return address to bits
    }

    private fun literalAddress(value: String): String {
        require(value.isNotEmpty() && value.length <= 45 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it in ".:" } &&
            (':' in value || value.split('.').let { it.size == 4 && it.all { part -> part.toIntOrNull()?.let { number -> number in 0..255 } == true } })) { "Invalid proxy IP address" }
        return InetAddress.getByName(value).hostAddress
    }

    private companion object {
        val LOOPBACK_DEFAULTS = setOf("127.0.0.1", "::1")
        val PROXY_DEFAULTS = setOf("127.0.0.0/8", "::1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7")
    }

    private fun origin(value: String): String? = runCatching {
        val uri = URI(value)
        "${uri.scheme}://${uri.host.lowercase()}:${if (uri.port == -1) 443 else uri.port}"
    }.getOrNull()
}

internal class NetworkLimits(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Source(var started: Long, var attempts: Int = 0, var connections: Int = 0, var logged: Long? = null)
    private val sources = LinkedHashMap<String, Source>()
    private var started = clock()
    private var attempts = 0
    private var connections = 0

    @Synchronized
    fun authenticate(source: String): Boolean {
        val time = clock()
        if (time - started >= 60_000) { started = time; attempts = 0 }
        val state = state(source) ?: return false
        return ++state.attempts <= 10 && ++attempts <= 60
    }
    @Synchronized
    fun open(source: String): Boolean {
        val state = state(source) ?: return false
        if (connections >= 64 || state.connections >= 8) return false
        connections++; state.connections++
        return true
    }
    @Synchronized
    fun audit(source: String, reason: String) {
        val state = state(source) ?: return
        val time = clock()
        if (state.logged?.let { time - it < 60_000 } == true) return
        state.logged = time
        log.warn { "Access denied: $reason (source=$source)" }
    }
    @Synchronized
    fun close(source: String) { sources[source]?.let { it.connections--; connections-- } }
    private fun state(source: String): Source? {
        val time = clock()
        sources.entries.removeIf { it.value.connections == 0 && time - it.value.started >= 60_000 }
        if (source !in sources && sources.size >= 4096) return null
        return sources.getOrPut(source) { Source(time) }.also {
            if (time - it.started >= 60_000) { it.started = time; it.attempts = 0 }
        }
    }
}
