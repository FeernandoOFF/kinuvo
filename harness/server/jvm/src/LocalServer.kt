package kinuvo.harness.server

import kinuvo.harness.core.store.readText
import kinuvo.harness.core.store.writeAtomically
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How the CLI reaches a Server running on this machine: where it listens, its process and a token that lasts until it stops. */
@Serializable
data class LocalServer(val host: String, val port: Int, val token: String, val pid: Long) {
    val alive get() = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    companion object {
        fun file(home: Path) = Path(home, "server.json")

        fun read(home: Path): LocalServer? = runCatching { Json.decodeFromString(serializer(), file(home).readText()) }.getOrNull()

        /** Readable by the owner only. */
        fun write(home: Path, server: LocalServer) = writeAtomically(file(home), Json.encodeToString(serializer(), server), private = true)

        fun delete(home: Path) = SystemFileSystem.delete(file(home), mustExist = false)
    }
}
