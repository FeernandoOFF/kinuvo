package kinuvo.harness.core.store

import kinuvo.harness.core.artifacts.ArtifactRepository
import kinuvo.protocol.ArtifactData
import kinuvo.protocol.ArtifactInfo
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json

/** `<id>.meta.json` with the details next to `<id>.<type>` with the content, in one folder; IDs have no dots, so names never clash. */
class FileArtifacts(private val dir: Path) : ArtifactRepository {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun list(): List<ArtifactInfo> = dir.children().filter { it.name.endsWith(META) && !it.name.startsWith(".") }
        .mapNotNull { f -> runCatching { json.decodeFromString(ArtifactInfo.serializer(), f.readText()) }.getOrNull() }

    override suspend fun get(id: String): ArtifactData? {
        val info = info(id)?.readTextOrNull()?.let { json.decodeFromString(ArtifactInfo.serializer(), it) } ?: return null
        return ArtifactData(info, content(info).readTextOrNull().orEmpty())
    }

    override suspend fun save(info: ArtifactInfo, content: String) {
        writeAtomically(content(info), content)
        writeAtomically(requireNotNull(info(info.id)), json.encodeToString(ArtifactInfo.serializer(), info))
    }

    override suspend fun delete(id: String): Boolean {
        val info = get(id)?.info ?: return false
        // Details first, so a half-done delete never lists an Artifact without content.
        SystemFileSystem.delete(requireNotNull(info(id)))
        SystemFileSystem.delete(content(info), mustExist = false)
        return true
    }

    /** Null for IDs that could name a file elsewhere. */
    private fun info(id: String) = id.takeIf { ID.matches(it) }?.let { Path(dir, "$it$META") }

    private fun content(info: ArtifactInfo) = Path(dir, "${info.id}.${info.type}")

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]*")
        const val META = ".meta.json"
    }
}
