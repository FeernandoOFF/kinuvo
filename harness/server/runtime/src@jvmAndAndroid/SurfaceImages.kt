package kinuvo.harness.server

import java.net.InetAddress
import java.net.Proxy
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kinuvo.protocol.SurfaceImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.io.encoding.Base64

internal fun publicImageAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    return if (bytes.size == 4) {
        val (a, b) = bytes
        a !in setOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
            !(a == 169 && b == 254) && !(a == 172 && b in 16..31) && !(a == 192 && b in setOf(0, 168)) &&
            !(a == 198 && b in 18..19)
    } else bytes.size == 16 && bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
        !(bytes.take(4) == listOf(0x20, 0x01, 0, 0))
}

internal fun imageUrl(raw: String): HttpUrl {
    require(raw.length <= 8192) { "Image URL is too long" }
    val url = raw.toHttpUrl()
    require(url.scheme in setOf("http", "https") && url.username.isEmpty() && url.password.isEmpty()) { "Use an HTTP(S) image URL without credentials" }
    return url
}

private val imageHttp = OkHttpClient.Builder()
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            require(addresses.isNotEmpty() && addresses.all(::publicImageAddress)) { "Image URL must resolve only to public addresses" }
        }
    })
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
    .build()

internal actual suspend fun fetchSurfaceImage(url: String): SurfaceImage = withContext(Dispatchers.IO) {
    var next = imageUrl(url)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    repeat(6) {
        require(System.nanoTime() < deadline) { "Image request timed out" }
        // Literal IP URLs bypass OkHttp's DNS hook.
        if (':' in next.host || next.host.all { it.isDigit() || it == '.' }) {
            require(publicImageAddress(InetAddress.getByName(next.host))) { "Image URL must be public" }
        }
        val call = imageHttp.newCall(Request.Builder().url(next).header("Accept", "image/*").header("User-Agent", SURFACE_IMAGE_USER_AGENT).build())
        call.timeout().timeout((deadline - System.nanoTime()).coerceIn(1, TimeUnit.SECONDS.toNanos(10)), TimeUnit.NANOSECONDS)
        call.execute().use { response ->
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                next = imageUrl(next.resolve(requireNotNull(response.header("Location")))?.toString() ?: error("Invalid image redirect"))
            } else {
                require(response.isSuccessful) { "Image request failed (${response.code})" }
                val body = requireNotNull(response.body)
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                require(mime in SURFACE_IMAGE_TYPES) { "URL must return a raster image" }
                require(body.contentLength() <= MAX_SURFACE_IMAGE_BYTES) { "Image exceeds 5 MB" }
                val bytes = body.byteStream().use { it.readBytesCapped() }
                return@withContext SurfaceImage(requireNotNull(mime), Base64.encode(bytes))
            }
        }
    }
    error("Too many image redirects")
}

internal fun InputStream.readBytesCapped(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= MAX_SURFACE_IMAGE_BYTES) { "Image exceeds 5 MB" }
        output.write(buffer, 0, count)
    }
}
