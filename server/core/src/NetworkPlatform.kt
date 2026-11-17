package kinuvo.server

import io.ktor.server.routing.Route

/** This machine's IPv4 addresses other Devices on the network can reach. */
expect fun lanAddresses(): List<String>

/** A literal IP address in [bytes] (4 for IPv4, 16 for IPv6) and its normalized [text]. */
internal class IpAddress(val bytes: ByteArray, val text: String) {
    val loopback get() = if (bytes.size == 4) bytes[0] == 127.toByte() else bytes.withIndex().all { (i, b) -> b == (if (i == 15) 1 else 0).toByte() }
    val any get() = bytes.all { it == 0.toByte() }
}

/** [host] as an address; throws IllegalArgumentException when it is no literal IP (the JVM also resolves names). */
internal expect fun ipAddress(host: String): IpAddress

internal expect fun portFree(host: String, port: Int): Boolean

/** An RFC 3986 URI's parts as `java.net.URI` reports them; [port] is -1 when absent. Throws on a malformed one. */
internal class UriParts(val scheme: String?, val host: String?, val port: Int, val path: String?, val userInfo: String?, val query: String?, val fragment: String?, val rawAuthority: String?)

internal expect fun parseUri(value: String): UriParts

internal expect fun secureRandomBytes(count: Int): ByteArray

/** Serves the bundled web app, where the platform bundles one. */
internal expect fun Route.webApp()
