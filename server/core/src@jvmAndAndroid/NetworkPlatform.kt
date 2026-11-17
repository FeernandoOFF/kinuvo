package kinuvo.server

import io.ktor.http.ContentType
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.security.SecureRandom
import java.util.Collections

/** This machine's IPv4 addresses other Devices on the network can reach. */
actual fun lanAddresses(): List<String> = Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { Collections.list(it.inetAddresses).asSequence() }
    .filterIsInstance<Inet4Address>()
    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    .map { it.hostAddress }
    .distinct().toList()

internal actual fun ipAddress(host: String): IpAddress = InetAddress.getByName(host).let { IpAddress(it.address, it.hostAddress) }

internal actual fun portFree(host: String, port: Int) = runCatching { ServerSocket().use { it.bind(InetSocketAddress(host, port)) } }.isSuccess

internal actual fun parseUri(value: String): UriParts = URI(value).let { UriParts(it.scheme, it.host, it.port, it.path, it.userInfo, it.query, it.fragment, it.rawAuthority) }

private val random = SecureRandom()
internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

internal actual fun Route.webApp() {
    staticResources("/", "web") {
        enableAutoHeadResponse()
        contentType { url -> when (url.path.substringAfterLast('.')) {
            "wasm" -> ContentType("application", "wasm")
            "mjs" -> ContentType.Application.JavaScript
            else -> null
        } }
    }
}
