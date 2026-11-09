package kinuvo.harness.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readBuffer
import kinuvo.protocol.SurfaceImage
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64

private val imageHttp by lazy {
    HttpClient(Darwin) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 5_000; socketTimeoutMillis = 5_000 }
    }
}

/** Unlike the JVM fetcher this can't vet DNS answers, so it refuses local names and private IP literals only. */
internal fun iosImageUrl(raw: String): Url {
    require(raw.length <= 8192) { "Image URL is too long" }
    val url = Url(raw)
    require(url.protocol.name in setOf("http", "https") && url.user == null && url.password == null) { "Use an HTTP(S) image URL without credentials" }
    val host = url.host.lowercase().trim('[', ']')
    require(host.isNotEmpty() && host != "localhost" && !host.endsWith(".local") && !host.endsWith(".localhost") && '.' in host) { "Image URL must be public" }
    val ipv4 = host.split('.').takeIf { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }?.map { it.toInt() }
    if (ipv4 != null) {
        val (a, b) = ipv4
        require(a !in setOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) && !(a == 192 && b == 168)) { "Image URL must be public" }
    }
    require(':' !in host) { "Image URL must be public" }
    return url
}

internal actual suspend fun fetchSurfaceImage(url: String): SurfaceImage = withTimeout(15_000) {
    var next = iosImageUrl(url)
    repeat(6) {
        val response = imageHttp.get(next) {
            header(HttpHeaders.Accept, "image/*")
            header(HttpHeaders.UserAgent, SURFACE_IMAGE_USER_AGENT)
        }
        if (response.status.value in setOf(301, 302, 303, 307, 308)) {
            val location = requireNotNull(response.headers[HttpHeaders.Location]) { "Invalid image redirect" }
            next = iosImageUrl(URLBuilder(next).takeFrom(location).buildString())
        } else {
            require(response.status.isSuccess()) { "Image request failed (${response.status.value})" }
            val mime = response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" }
            require(mime in SURFACE_IMAGE_TYPES) { "URL must return a raster image" }
            require((response.contentLength() ?: 0) <= MAX_SURFACE_IMAGE_BYTES) { "Image exceeds 5 MB" }
            val bytes = response.bodyAsChannel().readBuffer(MAX_SURFACE_IMAGE_BYTES + 1).readByteArray()
            require(bytes.size <= MAX_SURFACE_IMAGE_BYTES) { "Image exceeds 5 MB" }
            return@withTimeout SurfaceImage(requireNotNull(mime), Base64.encode(bytes))
        }
    }
    error("Too many image redirects")
}
