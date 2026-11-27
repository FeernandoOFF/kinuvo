package kinuvo.protocol.artifact

import kotlinx.serialization.Serializable

/** One file of an Artifact folder. [hash] is the SHA-256 of its bytes in hex. */
@Serializable
data class ArtifactFile(val path: String, val size: Long, val mime: String, val hash: String)

/** What an Artifact folder holds, without the content. [entry] is the page the Artifact opens at; [manifest] is null without a `manifest.json`. */
@Serializable
data class ArtifactFiles(val id: String, val entry: String = ArtifactBundle.INDEX, val files: List<ArtifactFile> = emptyList(), val manifest: ArtifactManifest? = null)

/** The optional `manifest.json` of an Artifact folder. Unknown keys are ignored so the schema can grow. */
@Serializable
data class ArtifactManifest(
    val entry: String = ArtifactBundle.INDEX,
    val title: String? = null,
    val capabilities: ArtifactCapabilities = ArtifactCapabilities(),
)

/** What an Artifact asks the owner to allow, or what they allowed. Every one defaults to denied; `tools` is kept for the host bridge and enforces nothing yet. */
@Serializable
data class ArtifactCapabilities(
    /** Origins the page may reach, e.g. `https://api.example.com`. */
    val network: List<String> = emptyList(),
    val popups: Boolean = false,
    val storage: Boolean = false,
    /** Names of tools the page may call. */
    val tools: List<String> = emptyList(),
)

/** A read of [path] from [offset]; the Server returns at most [length] bytes, so a big file arrives in pieces. */
@Serializable
data class ArtifactFileRef(val id: String, val path: String, val offset: Long = 0, val length: Int = ArtifactBundle.CHUNK_BYTES)

/** Up to [ArtifactBundle.CHUNK_BYTES] of a file from [offset] as [data] in base64; the file ends where `offset + bytes >= size`. */
@Serializable
data class ArtifactFileChunk(val path: String, val offset: Long, val size: Long, val mime: String, val data: String)

object ArtifactBundle {
    const val INDEX = "index.html"
    const val MANIFEST = "manifest.json"
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val MAX_BUNDLE_BYTES = 50 * 1024 * 1024
    const val MAX_FILES = 256
    const val MAX_PATH_CHARS = 200
    const val MAX_DEPTH = 8
    const val CHUNK_BYTES = 1024 * 1024

    /** [path] as stored: relative, `/`-separated, with no `.` or `..` and no hidden or odd segments. Throws for anything that could leave the folder. */
    fun safePath(path: String): String {
        require(path.isNotEmpty() && path.length <= MAX_PATH_CHARS) { "Paths are 1..$MAX_PATH_CHARS characters" }
        val segments = path.split('/')
        require(segments.size <= MAX_DEPTH) { "Paths are at most $MAX_DEPTH folders deep" }
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".") && it.none { c -> c.code < 32 || c == '\\' || c == ':' || c.code == 127 } }) {
            "'$path' is not a safe relative path: no leading '/', empty, '.', '..' or hidden segments"
        }
        return path
    }

    /** The MIME type files with this extension are served as, or null when the type isn't allowed in an Artifact folder. */
    fun mimeOf(path: String): String? = MIME[path.substringAfterLast('.', "").lowercase()]

    fun isText(mime: String) = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript") || mime.endsWith("xml") || mime.endsWith("svg+xml")

    private val MIME = mapOf(
        "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript", "mjs" to "text/javascript",
        "json" to "application/json", "map" to "application/json", "webmanifest" to "application/manifest+json", "wasm" to "application/wasm",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "xml" to "application/xml", "svg" to "image/svg+xml",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp", "avif" to "image/avif",
        "ico" to "image/x-icon", "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "mp4" to "video/mp4", "webm" to "video/webm",
        "sqlite" to "application/vnd.sqlite3", "sqlite3" to "application/vnd.sqlite3", "db" to "application/vnd.sqlite3", "bin" to "application/octet-stream",
    )

    val EXTENSIONS: Set<String> get() = MIME.keys
}
