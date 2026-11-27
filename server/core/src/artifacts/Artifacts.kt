package kinuvo.server.artifacts

import kinuvo.server.plugin.ARTIFACT_MAX_CHARS
import kinuvo.server.plugin.sha256

import kinuvo.protocol.artifact.ArtifactBundle
import kinuvo.protocol.artifact.ArtifactChange
import kinuvo.protocol.artifact.ArtifactData
import kinuvo.protocol.artifact.ArtifactFile
import kinuvo.protocol.artifact.ArtifactFileChunk
import kinuvo.protocol.artifact.ArtifactFiles
import kinuvo.protocol.artifact.ArtifactInfo
import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.protocol.artifact.ArtifactGrantChange
import kinuvo.protocol.artifact.ArtifactManifest
import kinuvo.protocol.artifact.ArtifactNetwork
import kinuvo.protocol.artifact.ArtifactPermissions
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.artifact.Design
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Where Artifacts are kept: each one's latest content and its [ArtifactInfo]. A bundle ([ArtifactInfo.bundle]) keeps its content
 * as files at safe relative paths instead: [get] gives it no content and [save] keeps only its details.
 */
interface ArtifactRepository {
    suspend fun list(): List<ArtifactInfo>
    suspend fun get(id: String): ArtifactData?
    suspend fun save(info: ArtifactInfo, content: String)
    suspend fun delete(id: String): Boolean

    /** The size of each file of a bundle by path; empty for any other Artifact. */
    suspend fun files(id: String): Map<String, Long>

    /** [length] bytes of a bundle's file from [offset], fewer at its end; null when it has no such file. */
    suspend fun readFile(id: String, path: String, offset: Long = 0, length: Int = Int.MAX_VALUE): ByteArray?
    suspend fun writeFile(id: String, path: String, bytes: ByteArray)
    suspend fun deleteFile(id: String, path: String): Boolean

    /** What the owner granted [id]; null when nothing. */
    suspend fun grants(id: String): ArtifactGrantRecord?

    /** Keeps [record] as [id]'s grants, or forgets them for null. */
    suspend fun saveGrants(id: String, record: ArtifactGrantRecord?)
}

/** The Artifacts every Agent shares; saving one replaces its content. Reads take the lock too, so they never see half a save. */
@OptIn(ExperimentalAtomicApi::class)
class Artifacts(private val repo: ArtifactRepository, private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val lock = Mutex()
    private val listeners = AtomicReference(emptyList<(ArtifactChange) -> Unit>())

    /** What pages share between the Devices showing them; goes with the Artifact when it is deleted. */
    val states = ArtifactStates()

    fun onChange(listener: (ArtifactChange) -> Unit) = listeners.update { it + listener }

    suspend fun list(): List<ArtifactInfo> = lock.withLock { repo.list() }.sortedByDescending { it.updated }

    suspend fun get(id: String): ArtifactData = lock.withLock { find(id) }

    /** A bundle's content is its entry page. */
    private suspend fun find(id: String): ArtifactData {
        val data = repo.get(id) ?: error("No artifact '$id'")
        return if (data.info.bundle) data.copy(content = repo.readFile(id, entryOf(id))?.decodeToString().orEmpty()) else data
    }

    private suspend fun newId(title: String): String {
        require(title.isNotBlank()) { "Give the artifact a title" }
        val taken = repo.list().map { it.id }.toSet()
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "artifact" }
        return generateSequence { "$slug-${Random.nextInt(0x10000).toString(16).padStart(4, '0')}" }.first { it !in taken }
    }

    suspend fun create(agentId: AgentId, sessionId: SessionId?, title: String, type: String, content: String): ArtifactInfo = lock.withLock {
        val id = newId(title)
        val now = clock()
        ArtifactInfo(id, title.trim(), checkedType(type), agentId, sessionId, now, now).also { save(it, content) }
    }

    /** An HTML Artifact kept as a folder of [files] by relative path, with its entry page among them. */
    suspend fun createBundle(agentId: AgentId, sessionId: SessionId?, title: String, files: Map<String, ByteArray>): ArtifactInfo = lock.withLock {
        val id = newId(title)
        val written = checkedFiles(emptyMap(), files)
        val entry = files[ArtifactBundle.MANIFEST]?.let { checkedManifest(it).entry } ?: ArtifactBundle.INDEX
        require(entry in files) { "Add the entry page '$entry'" }
        val now = clock()
        val info = ArtifactInfo(id, title.trim(), "html", agentId, sessionId, now, now, bundle = true)
        // Details last, so a failed write never lists an Artifact without its files.
        try {
            written.forEach { (path, bytes) -> repo.writeFile(id, path, bytes) }
            repo.save(info, "")
        } catch (e: Exception) {
            runCatching { repo.delete(id) }
            throw e
        }
        info.also { notify(ArtifactChange(id, it)) }
    }

    /** What [id] holds as files; an Artifact that isn't a bundle is one file. */
    suspend fun files(id: String): ArtifactFiles = lock.withLock {
        val data = find(id)
        if (!data.info.bundle) {
            val bytes = data.content.encodeToByteArray()
            val path = "index.${data.info.type}"
            return@withLock ArtifactFiles(id, path, listOf(ArtifactFile(path, bytes.size.toLong(), ArtifactBundle.mimeOf(path) ?: OCTET, hashOf(bytes))))
        }
        val manifest = manifestOf(id)
        val files = repo.files(id).entries.sortedBy { it.key }.map { (path, size) -> ArtifactFile(path, size, ArtifactBundle.mimeOf(path) ?: OCTET, hash(id, path, size)) }
        ArtifactFiles(id, manifest?.entry ?: ArtifactBundle.INDEX, files, manifest)
    }

    /** Up to [length] bytes of one file of [id] from [offset], and never more than a chunk. */
    suspend fun readFile(id: String, path: String, offset: Long = 0, length: Int = ArtifactBundle.CHUNK_BYTES): ArtifactFileChunk = lock.withLock {
        require(offset >= 0 && length > 0) { "Read a positive length from a non-negative offset" }
        val data = find(id)
        val mime = ArtifactBundle.mimeOf(path) ?: OCTET
        val take = minOf(length, ArtifactBundle.CHUNK_BYTES)
        if (!data.info.bundle) {
            check(path == "index.${data.info.type}") { "No file '$path' in '$id'" }
            val bytes = data.content.encodeToByteArray()
            val end = minOf(bytes.size.toLong(), offset + take).toInt()
            return@withLock chunk(path, mime, offset, bytes.size.toLong(), bytes.copyOfRange(offset.coerceAtMost(end.toLong()).toInt(), end))
        }
        val size = repo.files(id)[path] ?: error("No file '$path' in '$id'")
        chunk(path, mime, offset, size, repo.readFile(id, path, offset, take) ?: error("No file '$path' in '$id'"))
    }

    private fun chunk(path: String, mime: String, offset: Long, size: Long, bytes: ByteArray) = ArtifactFileChunk(path, offset, size, mime, Base64.encode(bytes))

    /** The whole of one file of a bundle. */
    suspend fun fileBytes(id: String, path: String): ByteArray = lock.withLock {
        require(find(id).info.bundle) { "'$id' is not a folder; use its content" }
        repo.readFile(id, ArtifactBundle.safePath(path)) ?: error("No file '$path' in '$id'")
    }

    /** Writes [files] into [id], which becomes a folder if it isn't one yet; a `manifest.json` among them must parse. */
    suspend fun writeFiles(id: String, agentId: AgentId, sessionId: SessionId?, files: Map<String, ByteArray>): ArtifactInfo = lock.withLock {
        val old = find(id)
        require(old.info.type == "html") { "Only html artifacts can hold files" }
        val existing = if (old.info.bundle) repo.files(id) else mapOf(ArtifactBundle.INDEX to old.content.encodeToByteArray().size.toLong())
        val written = checkedFiles(existing, files)
        files[ArtifactBundle.MANIFEST]?.let { checkedManifest(it) }
        if (!old.info.bundle && ArtifactBundle.INDEX !in files) repo.writeFile(id, ArtifactBundle.INDEX, old.content.encodeToByteArray())
        written.forEach { (path, bytes) -> repo.writeFile(id, path, bytes) }
        forget(id, written.keys)
        if (ArtifactBundle.MANIFEST in written) prune(id)
        touched(old.info, agentId, sessionId)
    }

    /** Removes [path] from the folder [id]; the entry page can be replaced but a folder can't lose it and stay openable. */
    suspend fun deleteFile(id: String, agentId: AgentId, sessionId: SessionId?, path: String): ArtifactInfo = lock.withLock {
        val old = find(id)
        require(old.info.bundle) { "'$id' has no files to delete" }
        require(path != entryOf(id)) { "'$path' is the entry page; replace it instead" }
        check(repo.deleteFile(id, ArtifactBundle.safePath(path))) { "No file '$path' in '$id'" }
        forget(id, setOf(path))
        if (path == ArtifactBundle.MANIFEST) prune(id)
        touched(old.info, agentId, sessionId)
    }

    /** What the manifest of [id] asks for, and what its owner allowed; an Artifact without a manifest asks for nothing. */
    suspend fun permissions(id: String): ArtifactPermissions = lock.withLock { permissionsOf(find(id).info) }

    /** Applies [change] to what the owner granted [id] on behalf of the Device [deviceId], and tells every Device. Only what the manifest asks for can be granted. */
    suspend fun changeGrants(id: String, change: ArtifactGrantChange, deviceId: String?, deviceName: String?): ArtifactPermissions = lock.withLock {
        val info = find(id).info
        val requested = requestedOf(info)
        val add = change.grant.cleaned()
        require(add.network.all { it in requested.network }) { "The artifact doesn't ask for network access to ${add.network.first { it !in requested.network }}" }
        require(!add.popups || requested.popups) { "The artifact doesn't ask for pop-ups" }
        require(!add.storage || requested.storage) { "The artifact doesn't ask for storage" }
        require(add.tools.all { it in requested.tools }) { "The artifact doesn't ask for the tool ${add.tools.first { it !in requested.tools }}" }
        val start = when (change.all) {
            true -> requested
            false -> NONE
            null -> repo.grants(id)?.granted ?: NONE
        }
        val granted = (start + add) - change.revoke.copy(network = change.revoke.network.map { ArtifactNetwork.origin(it) ?: it })
        keepGrants(id, granted, deviceId, deviceName)
        notify(ArtifactChange(id, info))
        permissionsOf(info)
    }

    private suspend fun permissionsOf(info: ArtifactInfo): ArtifactPermissions {
        val requested = requestedOf(info)
        val record = repo.grants(info.id)
        val granted = record?.granted ?: NONE
        return ArtifactPermissions(info.id, requested, granted, effective(requested, granted), record?.at, record?.by, record?.byName)
    }

    private suspend fun requestedOf(info: ArtifactInfo): ArtifactCapabilities =
        if (info.bundle) manifestOf(info.id)?.capabilities?.cleaned() ?: NONE else NONE

    private suspend fun keepGrants(id: String, granted: ArtifactCapabilities, deviceId: String?, deviceName: String?) =
        repo.saveGrants(id, if (granted == NONE) null else ArtifactGrantRecord(granted, clock(), deviceId, deviceName))

    /** Drops grants the manifest no longer asks for, so asking again later needs a new grant. */
    private suspend fun prune(id: String) {
        val record = repo.grants(id) ?: return
        val kept = effective(requestedOf(find(id).info), record.granted)
        if (kept != record.granted) repo.saveGrants(id, if (kept == NONE) null else record.copy(granted = kept))
    }

    private suspend fun touched(info: ArtifactInfo, agentId: AgentId, sessionId: SessionId?): ArtifactInfo =
        info.copy(agentId = agentId, sessionId = sessionId, updated = clock(), bundle = true).also { repo.save(it, ""); notify(ArtifactChange(it.id, it)) }

    private suspend fun entryOf(id: String) = manifestOf(id)?.entry ?: ArtifactBundle.INDEX

    private suspend fun manifestOf(id: String): ArtifactManifest? =
        repo.readFile(id, ArtifactBundle.MANIFEST)?.let { runCatching { json.decodeFromString<ArtifactManifest>(it.decodeToString()) }.getOrNull() }

    /** [writes] merged over what the folder [existing] holds, with each file and the whole held to their limits. */
    private fun checkedFiles(existing: Map<String, Long>, writes: Map<String, ByteArray>): Map<String, ByteArray> {
        val safe = writes.mapKeys { (path, _) -> ArtifactBundle.safePath(path) }
        safe.forEach { (path, bytes) ->
            require(ArtifactBundle.mimeOf(path) != null) { "'$path' has a type Artifacts don't hold; allowed extensions: ${ArtifactBundle.EXTENSIONS.sorted().joinToString()}" }
            require(bytes.size <= ArtifactBundle.MAX_FILE_BYTES) { "'$path' is over ${ArtifactBundle.MAX_FILE_BYTES / 1_000_000} MB" }
        }
        val merged = existing + safe.mapValues { it.value.size.toLong() }
        require(merged.size <= ArtifactBundle.MAX_FILES) { "An artifact holds at most ${ArtifactBundle.MAX_FILES} files" }
        require(merged.values.sum() <= ArtifactBundle.MAX_BUNDLE_BYTES) { "An artifact is limited to ${ArtifactBundle.MAX_BUNDLE_BYTES / 1_000_000} MB in all" }
        require(merged.keys.map { it.lowercase() }.toSet().size == merged.size) { "File paths must differ by more than letter case" }
        return safe
    }

    private fun checkedManifest(bytes: ByteArray): ArtifactManifest {
        val manifest = try {
            json.decodeFromString<ArtifactManifest>(bytes.decodeToString())
        } catch (e: Exception) {
            throw IllegalArgumentException("manifest.json must be JSON like {\"entry\":\"index.html\",\"title\":\"...\",\"capabilities\":{\"network\":[],\"popups\":false,\"storage\":false,\"tools\":[]}}: ${e.message?.lineSequence()?.first()}")
        }
        ArtifactBundle.safePath(manifest.entry)
        require(manifest.entry.substringAfterLast('.').lowercase() in setOf("html", "htm")) { "The entry page must be an .html file" }
        ArtifactNetwork.checked(manifest.capabilities.network)
        require(manifest.capabilities.tools.size <= MAX_TOOLS && manifest.capabilities.tools.all { it.isNotBlank() && it.length <= 64 }) { "An artifact may ask for at most $MAX_TOOLS tools, each named in up to 64 characters" }
        return manifest
    }

    private val hashes = mutableMapOf<String, Pair<Long, String>>()

    /** Cached by path and size, until a write or delete here touches the file. */
    private suspend fun hash(id: String, path: String, size: Long): String {
        val key = "$id/$path"
        hashes[key]?.takeIf { it.first == size }?.let { return it.second }
        val hex = hashOf(repo.readFile(id, path) ?: return "")
        hashes[key] = size to hex
        return hex
    }

    private fun forget(id: String, paths: Set<String>) = paths.forEach { hashes.remove("$id/$it") }

    private fun hashOf(bytes: ByteArray) = sha256(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Replaces [id]'s content with what [transform] makes of it, and its title when [title] is set. */
    suspend fun update(id: String, agentId: AgentId, sessionId: SessionId?, title: String? = null, transform: (String) -> String): ArtifactInfo = lock.withLock {
        val old = find(id)
        val info = old.info.copy(title = title?.trim()?.ifEmpty { null } ?: old.info.title, agentId = agentId, sessionId = sessionId, updated = clock())
        info.also { saveContent(it, transform(old.content)) }
    }

    /** Replaces [id]'s content by the user, keeping the Agent and Session it was last saved from. */
    suspend fun replace(id: String, content: String): ArtifactInfo = lock.withLock {
        find(id).info.copy(updated = clock()).also { saveContent(it, content) }
    }

    suspend fun delete(id: String) = lock.withLock {
        check(repo.delete(id)) { "No artifact '$id'" }
        hashes.keys.removeAll { it.startsWith("$id/") }
        repo.saveGrants(id, null)
        states.clear(id)
        notify(ArtifactChange(id))
    }

    /** [content] as the whole of a single file, or the entry page of a bundle. */
    private suspend fun saveContent(info: ArtifactInfo, content: String) {
        if (!info.bundle) return save(info, content)
        val entry = entryOf(info.id)
        val bytes = content.encodeToByteArray()
        require(bytes.size <= ArtifactBundle.MAX_FILE_BYTES) { "'$entry' is over ${ArtifactBundle.MAX_FILE_BYTES / 1_000_000} MB" }
        checkedFiles(repo.files(info.id), mapOf(entry to bytes))
        repo.writeFile(info.id, entry, bytes)
        forget(info.id, setOf(entry))
        repo.save(info, "")
        notify(ArtifactChange(info.id, info))
    }

    private suspend fun save(info: ArtifactInfo, content: String) {
        require(content.length <= MAX_CHARS) { "Artifacts are limited to ${MAX_CHARS / 1_000_000} MB" }
        if (info.type == "design") checkedDesign(content)
        repo.save(info, content)
        notify(ArtifactChange(info.id, info))
    }

    private fun notify(change: ArtifactChange) = listeners.load().forEach { runCatching { it(change) } }

    companion object {
        val TYPES = listOf("html", "svg", "md", "csv", "json", "txt", "design")
        private val json = Json { ignoreUnknownKeys = true }
        const val MAX_SIDE = 10_000
        private const val MAX_TOOLS = 32
        const val MAX_CHARS = ARTIFACT_MAX_CHARS
        private const val OCTET = "application/octet-stream"

        fun parseDesign(content: String): Design = try {
            json.decodeFromString<Design>(content)
        } catch (e: Exception) {
            throw IllegalArgumentException("Design must be JSON like {\"artboards\":[{\"id\",\"name\",\"width\",\"height\",\"html\"}]}: ${e.message?.lineSequence()?.first()}")
        }

        fun encodeDesign(design: Design) = json.encodeToString(Design.serializer(), design)

        fun checkedDesign(content: String) = parseDesign(content).also { design ->
            require(design.artboards.all { it.id.isNotBlank() }) { "Artboard ids can't be blank" }
            require(design.artboards.map { it.id }.toSet().size == design.artboards.size) { "Artboard ids must be unique" }
            design.artboards.forEach {
                require(it.width in 1..MAX_SIDE && it.height in 1..MAX_SIDE) { "Artboard '${it.id}' width and height must be 1..$MAX_SIDE" }
                require(listOfNotNull(it.x, it.y).all { c -> c in -MAX_SIDE * 100..MAX_SIDE * 100 }) { "Artboard '${it.id}' x and y are out of range" }
            }
        }

        fun checkedType(type: String) = type.lowercase().removePrefix(".").let { t ->
            when (t) {
                "htm" -> "html"
                "markdown" -> "md"
                else -> t.also { require(it in TYPES) { "Type must be one of ${TYPES.joinToString()}" } }
            }
        }
    }
}
