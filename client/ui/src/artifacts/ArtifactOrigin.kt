package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlin.concurrent.Volatile

internal const val INDEX = "index.html"

internal class ArtifactFile(val mime: String, val bytes: ByteArray)

/** What one Artifact serves: files by path, `index.html` being the page. */
internal class ArtifactSite(val id: String, val files: Map<String, ArtifactFile>) {
    val version = files.entries.sortedBy { it.key }.fold(0) { h, (path, file) -> 31 * (31 * h + path.hashCode()) + file.bytes.contentHashCode() }.toUInt().toString(16)

    companion object {
        fun page(id: String, html: String) = ArtifactSite(id, mapOf(INDEX to ArtifactFile(mimeOf(INDEX), html.encodeToByteArray())))
    }
}

internal class OriginResponse(val status: Int, val mime: String, val headers: Map<String, String>, val body: ByteArray) {
    val contentType get() = if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript") || mime.endsWith("svg+xml")) "$mime; charset=utf-8" else mime
}

/** Where a web view reaches [site], at [base] (scheme, host and port, no trailing slash); every origin serves one Artifact and refuses anything outside it. */
internal class ArtifactOrigin(val base: String, site: ArtifactSite) {
    @Volatile
    var site: ArtifactSite = site

    val url get() = "$base/$INDEX?v=${site.version}"

    fun owns(url: String) = url.startsWith("$base/", ignoreCase = true)

    /** The file [url] asks for, or a refusal when it is outside this origin or its path leaves the site. */
    fun respond(url: String): OriginResponse {
        val path = if (owns(url)) pathOf(url.substring(base.length + 1)) else null
        val file = path?.let { site.files[it] }
        return when {
            path == null -> refusal(403)
            file == null -> refusal(404)
            else -> OriginResponse(200, file.mime, artifactHeaders(file.mime), file.bytes)
        }
    }

    private fun refusal(status: Int) = OriginResponse(status, "text/plain", artifactHeaders("text/plain"), byteArrayOf())
}

private val ID = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** Whether [id] can be a DNS label, which every platform's origin address needs; others stay in a sandboxed frame. */
internal fun originId(id: String) = ID.matches(id)

/** The path in [rest], the part of a URL after its first slash; `index.html` for none, and null if it leaves the site. */
internal fun pathOf(rest: String): String? {
    val raw = rest.substringBefore('#').substringBefore('?')
    if (raw.isEmpty()) return INDEX
    val segments = raw.split('/').map { decodePercent(it) ?: return null }
    if (segments.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\\' in it || '\u0000' in it }) return null
    return segments.joinToString("/")
}

private fun decodePercent(text: String): String? {
    if ('%' !in text) return text
    val raw = text.encodeToByteArray()
    val out = ByteArray(raw.size)
    var n = 0
    var i = 0
    while (i < raw.size) {
        if (raw[i] == '%'.code.toByte()) {
            if (i + 2 >= raw.size) return null
            val high = raw[i + 1].toInt().toChar().digitToIntOrNull(16) ?: return null
            val low = raw[i + 2].toInt().toChar().digitToIntOrNull(16) ?: return null
            out[n++] = (high * 16 + low).toByte()
            i += 3
        } else out[n++] = raw[i++]
    }
    return out.copyOf(n).decodeToString()
}

private val MIME = mapOf(
    "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript", "mjs" to "text/javascript",
    "json" to "application/json", "map" to "application/json", "wasm" to "application/wasm", "txt" to "text/plain", "csv" to "text/csv",
    "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "webp" to "image/webp", "avif" to "image/avif", "ico" to "image/x-icon", "woff" to "font/woff", "woff2" to "font/woff2",
    "ttf" to "font/ttf", "otf" to "font/otf", "mp3" to "audio/mpeg", "wav" to "audio/wav", "mp4" to "video/mp4", "webm" to "video/webm",
)

internal fun mimeOf(path: String) = MIME[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

/** Every response is sent with these; documents also carry the [artifactPolicy] that scripts and styles are held to. */
internal fun artifactHeaders(mime: String): Map<String, String> = buildMap {
    put("X-Content-Type-Options", "nosniff")
    put("Referrer-Policy", "no-referrer")
    put("Cache-Control", "no-store")
    if (mime == "text/html") put("Content-Security-Policy", artifactPolicy())
}

/** The origin of one Artifact, opened where the platform's web view can serve one and null where it cannot; [closeOrigin] releases it. */
internal expect fun openOrigin(site: ArtifactSite): ArtifactOrigin?

internal expect fun closeOrigin(origin: ArtifactOrigin)

/** [site] at an origin of its own for as long as the caller stays, the same origin while its content changes; null without [site] or where the platform has none. */
@Composable
internal fun rememberArtifactOrigin(site: ArtifactSite?): ArtifactOrigin? {
    val origin = remember(site?.id) { site?.let(::openOrigin) }
    DisposableEffect(origin) { onDispose { origin?.let(::closeOrigin) } }
    if (origin != null && site != null) origin.site = site
    return origin
}
