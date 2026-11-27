package kinuvo.ui.artifacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kinuvo.protocol.artifact.ArtifactBundle
import kinuvo.protocol.artifact.ArtifactFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

internal const val INDEX = "index.html"

internal class SiteFile(val mime: String, val bytes: ByteArray)

/** A file the Server has that the site fetches when a page first asks, identified by [hash]. */
internal class RemoteFile(val mime: String, val hash: String)

/**
 * What one Artifact serves: files by path, [entry] being the page. [remote] are the rest of an Artifact folder, read through [fetch]
 * on first use so a big `.wasm` crosses the wire only when a page loads it.
 */
internal class ArtifactSite(
    val id: String,
    val files: Map<String, SiteFile>,
    val entry: String = INDEX,
    val remote: Map<String, RemoteFile> = emptyMap(),
    val fetch: suspend (String) -> ByteArray? = { null },
    val access: ArtifactAccess = ArtifactAccess.None,
) {
    val version = (31 * access.hashCode() + files.entries.sortedBy { it.key }.fold(0) { h, (path, file) -> 31 * (31 * h + path.hashCode()) + file.bytes.contentHashCode() } +
        remote.entries.sortedBy { it.key }.fold(0) { h, (path, file) -> 31 * (31 * h + path.hashCode()) + file.hash.hashCode() }).toUInt().toString(16)

    companion object {
        fun page(id: String, html: String, access: ArtifactAccess = ArtifactAccess.None) =
            ArtifactSite(id, mapOf(INDEX to SiteFile(mimeOf(INDEX), html.encodeToByteArray())), access = access)

        /** The folder [listing] with its entry page [html]; every other file is fetched on demand. */
        fun folder(id: String, listing: ArtifactFiles, html: String, access: ArtifactAccess = ArtifactAccess.None, fetch: suspend (String) -> ByteArray?) = ArtifactSite(
            id, mapOf(listing.entry to SiteFile(mimeOf(listing.entry), html.encodeToByteArray())), listing.entry,
            listing.files.filter { it.path != listing.entry }.associate { it.path to RemoteFile(it.mime, it.hash) }, fetch, access,
        )
    }
}

internal class OriginResponse(val status: Int, val mime: String, val headers: Map<String, String>, val body: ByteArray) {
    val contentType get() = if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript") || mime.endsWith("svg+xml")) "$mime; charset=utf-8" else mime
}

/** Where a web view reaches [site], at [base] (scheme, host and port, no trailing slash); every origin serves one Artifact and refuses anything outside it. */
internal class ArtifactOrigin(val base: String, site: ArtifactSite) {
    @Volatile
    var site: ArtifactSite = site

    private val fetched = Mutex()
    private val cache = HashMap<String, Pair<String, SiteFile>>()

    val url get() = "$base/${encodePath(site.entry)}?v=${site.version}"

    fun owns(url: String) = url.startsWith("$base/", ignoreCase = true)

    /** The file [url] asks for among those held, or a refusal when it is outside this origin or its path leaves the site; files not fetched yet are not found. */
    fun respond(url: String): OriginResponse {
        val site = site
        val path = if (owns(url)) pathOf(url.substring(base.length + 1), site.entry) else null
        return answer(path, path?.let { site.files[it] })
    }

    /** Like [respond], fetching a file of the site's folder on its first request and keeping it for as long as the page is shown. */
    suspend fun serve(url: String): OriginResponse {
        val site = site
        val path = if (owns(url)) pathOf(url.substring(base.length + 1), site.entry) else null
        val held = path?.let { site.files[it] }
        if (path == null || held != null) return answer(path, held)
        val remote = site.remote[path] ?: return answer(path, null)
        val cached = fetched.withLock { cache[path]?.takeIf { it.first == remote.hash }?.second }
        if (cached != null) return answer(path, cached)
        val bytes = try { site.fetch(path) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return refusal(502)
        return answer(path, SiteFile(remote.mime, bytes).also { file -> fetched.withLock { cache[path] = remote.hash to file } })
    }

    private fun answer(path: String?, file: SiteFile?) = when {
        path == null -> refusal(403)
        file == null -> refusal(404)
        else -> OriginResponse(200, file.mime, artifactHeaders(file.mime, site.access), file.bytes)
    }

    private fun refusal(status: Int) = OriginResponse(status, "text/plain", artifactHeaders("text/plain", site.access), byteArrayOf())
}

private val ID = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** Whether [id] can be a DNS label, which every platform's origin address needs; others stay in a sandboxed frame. */
internal fun originId(id: String) = ID.matches(id)

/** The path in [rest], the part of a URL after its first slash; [entry] for none, and null if it leaves the site. */
internal fun pathOf(rest: String, entry: String = INDEX): String? {
    val raw = rest.substringBefore('#').substringBefore('?')
    if (raw.isEmpty()) return entry
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

internal fun mimeOf(path: String) = ArtifactBundle.mimeOf(path) ?: "application/octet-stream"

/** [path] for a URL: each segment's bytes outside the unreserved set as `%XX`. */
internal fun encodePath(path: String) = path.split('/').joinToString("/") { segment ->
    segment.encodeToByteArray().joinToString("") { b ->
        val c = b.toInt() and 0xff
        if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~")) c.toChar().toString() else "%" + c.toString(16).uppercase().padStart(2, '0')
    }
}

/** Every response is sent with these; documents also carry the [artifactPolicy] that scripts and styles are held to, widened by what [access] grants. */
internal fun artifactHeaders(mime: String, access: ArtifactAccess = ArtifactAccess.None): Map<String, String> = buildMap {
    put("X-Content-Type-Options", "nosniff")
    put("Referrer-Policy", "no-referrer")
    put("Cache-Control", "no-store")
    if (mime == "text/html") put("Content-Security-Policy", artifactPolicy(own = true, access = access))
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
