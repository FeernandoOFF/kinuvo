package kinuvo.server.plugin

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Where a token Kinuvo signs in with is kept: the OS keychain, or a file only the owner can read. */
interface Keychain {
    val name: String
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun delete()

    companion object
}

/** A token in a file only its owner can read. */
class PrivateFile(private val path: Path) : Keychain {
    override val name = path.toString()
    override suspend fun read() = path.readTextOrNull()
    override suspend fun write(token: String) = writeAtomically(path, token, private = true)
    override suspend fun delete() = SystemFileSystem.delete(path, mustExist = false)
}
