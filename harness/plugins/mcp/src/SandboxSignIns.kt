package kinuvo.harness.mcp

import kinuvo.harness.core.store.deleteTree
import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json

/** Sign-ins as private files in an app's sandbox, which only it can read. */
internal class SandboxSignIns(private val dir: Path) : McpSignInStore {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun get(id: String) = file(id).readTextOrNull()?.let { runCatching { json.decodeFromString(McpSignIn.serializer(), it) }.getOrNull() }
    override suspend fun put(id: String, signIn: McpSignIn) = writeAtomically(file(id), json.encodeToString(McpSignIn.serializer(), signIn), private = true)
    override suspend fun remove(id: String) = SystemFileSystem.delete(file(id), mustExist = false)
    override suspend fun removeAll(prefix: String) = deleteTree(Path(dir.toString(), *prefix.split('/').also(::checkSignInId).toTypedArray()))
    private fun file(id: String) = Path(dir.toString(), *id.split('/').also(::checkSignInId).toTypedArray()).let { Path(it.parent!!, "${it.name}.json") }
}
