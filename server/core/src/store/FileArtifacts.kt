package kinuvo.server.store

import kinuvo.server.plugin.children
import kinuvo.server.plugin.createDirectories
import kinuvo.server.plugin.deleteTree
import kinuvo.server.plugin.isDirectory
import kinuvo.server.plugin.isFile
import kinuvo.server.plugin.isInside
import kinuvo.server.plugin.readBytes
import kinuvo.server.plugin.readText
import kinuvo.server.plugin.readTextOrNull
import kinuvo.server.plugin.size
import kinuvo.server.plugin.writeAtomically
import kinuvo.protocol.artifact.ArtifactBundle
import kinuvo.server.artifacts.ArtifactGrantRecord
import kinuvo.server.artifacts.ArtifactRepository
import kinuvo.protocol.artifact.ArtifactData
import kinuvo.protocol.artifact.ArtifactInfo
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json

/**
 * `<id>.meta.json` with the details next to `<id>.<type>` with the content, in one folder; IDs have no dots, so names never clash.
 * A bundle keeps its files in a folder `<id>/` instead of `<id>.<type>`. What the owner granted it is `<id>.grants.json`.
 */
class FileArtifacts(private val dir: Path) : ArtifactRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun list(): List<ArtifactInfo> = dir.children().filter { it.name.endsWith(META) && !it.name.startsWith(".") }
        .mapNotNull { f -> runCatching { json.decodeFromString(ArtifactInfo.serializer(), f.readText()) }.getOrNull() }

    override suspend fun get(id: String): ArtifactData? {
        val info = info(id)?.readTextOrNull()?.let { json.decodeFromString(ArtifactInfo.serializer(), it) } ?: return null
        return ArtifactData(info, if (info.bundle) "" else content(info).readTextOrNull().orEmpty())
    }

    override suspend fun save(info: ArtifactInfo, content: String) {
        if (!info.bundle) writeAtomically(content(info), content)
        writeAtomically(requireNotNull(info(info.id)), json.encodeToString(ArtifactInfo.serializer(), info))
        // A single file that became a bundle: its folder has the content now.
        if (info.bundle) SystemFileSystem.delete(content(info), mustExist = false)
    }

    override suspend fun delete(id: String): Boolean {
        val info = get(id)?.info ?: return false
        // Details first, so a half-done delete never lists an Artifact without content.
        SystemFileSystem.delete(requireNotNull(info(id)))
        SystemFileSystem.delete(content(info), mustExist = false)
        folder(id)?.takeIf { it.isDirectory() }?.let(::deleteTree)
        return true
    }

    override suspend fun files(id: String): Map<String, Long> {
        val root = folder(id)?.takeIf { it.isDirectory() } ?: return emptyMap()
        return buildMap { walk(root, root, "") }
    }

    private fun MutableMap<String, Long>.walk(root: Path, dir: Path, prefix: String) {
        for (child in dir.children()) {
            if (child.name.startsWith(".") || !isInside(root, child)) continue
            if (child.isDirectory()) walk(root, child, "$prefix${child.name}/") else child.size()?.let { put("$prefix${child.name}", it) }
        }
    }

    override suspend fun readFile(id: String, path: String, offset: Long, length: Int): ByteArray? {
        val file = file(id, path)?.takeIf { it.isFile() } ?: return null
        return if (offset == 0L && length == Int.MAX_VALUE) file.readBytes() else file.readBytes(offset, length)
    }

    override suspend fun writeFile(id: String, path: String, bytes: ByteArray) {
        val root = requireNotNull(folder(id)) { "No artifact '$id'" }.also { it.createDirectories() }
        val file = Path(root, ArtifactBundle.safePath(path))
        require(isInside(root, file)) { "'$path' is outside the artifact" }
        writeAtomically(file, bytes)
    }

    override suspend fun deleteFile(id: String, path: String): Boolean {
        val file = file(id, path)?.takeIf { it.isFile() } ?: return false
        val root = requireNotNull(folder(id))
        SystemFileSystem.delete(file)
        var parent = file.parent
        while (parent != null && parent != root && parent.children().isEmpty()) {
            SystemFileSystem.delete(parent)
            parent = parent.parent
        }
        return true
    }

    override suspend fun grants(id: String): ArtifactGrantRecord? =
        grantsFile(id)?.readTextOrNull()?.let { runCatching { json.decodeFromString(ArtifactGrantRecord.serializer(), it) }.getOrNull() }

    override suspend fun saveGrants(id: String, record: ArtifactGrantRecord?) {
        val file = grantsFile(id) ?: return
        if (record == null) SystemFileSystem.delete(file, mustExist = false) else writeAtomically(file, json.encodeToString(ArtifactGrantRecord.serializer(), record))
    }

    private fun grantsFile(id: String) = id.takeIf { ID.matches(it) }?.let { Path(dir, "$it$GRANTS") }

    private fun folder(id: String) = id.takeIf { ID.matches(it) }?.let { Path(dir, it) }

    /** Null for paths that leave the folder, by name or through a link. */
    private fun file(id: String, path: String): Path? {
        val root = folder(id)?.takeIf { it.isDirectory() } ?: return null
        val safe = runCatching { ArtifactBundle.safePath(path) }.getOrNull() ?: return null
        return Path(root, safe).takeIf { isInside(root, it) }
    }

    /** Null for IDs that could name a file elsewhere. */
    private fun info(id: String) = id.takeIf { ID.matches(it) }?.let { Path(dir, "$it$META") }

    private fun content(info: ArtifactInfo) = Path(dir, "${info.id}.${info.type}")

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]*")
        const val META = ".meta.json"
        const val GRANTS = ".grants.json"
    }
}
