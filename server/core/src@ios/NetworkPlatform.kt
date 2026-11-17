package kinuvo.server

import io.ktor.server.routing.Route
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSURLComponents
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.INET6_ADDRSTRLEN
import platform.darwin.inet_ntop
import platform.darwin.inet_pton

/** The iPhone Server is reached only through Tailcat, never on the local network. */
actual fun lanAddresses(): List<String> = emptyList()

@OptIn(ExperimentalForeignApi::class)
internal actual fun ipAddress(host: String): IpAddress {
    val literal = if (host == "localhost") "127.0.0.1" else host.removeSurrounding("[", "]")
    for ((family, size) in listOf(AF_INET to 4, AF_INET6 to 16)) {
        val bytes = ByteArray(size)
        if (bytes.usePinned { inet_pton(family, literal, it.addressOf(0)) } != 1) continue
        // Like the JVM, an IPv4-mapped IPv6 address is the IPv4 address.
        if (size == 16 && (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte())
            return ipAddress(bytes.copyOfRange(12, 16).joinToString(".") { (it.toInt() and 0xff).toString() })
        val text = ByteArray(INET6_ADDRSTRLEN).let { out ->
            bytes.usePinned { b -> out.usePinned { o -> inet_ntop(family, b.addressOf(0), o.addressOf(0), out.size.convert()) } }
            out.toKString()
        }
        return IpAddress(bytes, text)
    }
    throw IllegalArgumentException("Not an IP address: $host")
}

internal actual fun portFree(host: String, port: Int) = true

internal actual fun parseUri(value: String): UriParts {
    val uri = requireNotNull(NSURLComponents.componentsWithString(value)) { "Invalid URI" }
    val authority = value.substringAfter("://", "").takeIf { "://" in value }?.takeWhile { it !in "/?#" }
    return UriParts(uri.scheme, uri.percentEncodedHost, uri.port?.intValue ?: -1, uri.percentEncodedPath,
        uri.percentEncodedUser?.let { user -> uri.percentEncodedPassword?.let { "$user:$it" } ?: user }, uri.percentEncodedQuery, uri.percentEncodedFragment, authority)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { bytes ->
    bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) == 0) { "No secure random bytes" } }
}

internal actual fun Route.webApp() {}
