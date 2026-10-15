package kinuvo.harness.core

import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Where a token Kinuvo signs in with is kept: the OS keychain, or a file only the owner can read. */
interface Keychain {
    val name: String
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun delete()

    companion object {
        /** The host's keychain, or null when it has none (e.g. headless Linux or a container). */
        fun system(service: String, account: String): Keychain? = systemKeychain(service, account)
    }
}

internal expect fun systemKeychain(service: String, account: String): Keychain?

/** A token in a file only its owner can read. */
class PrivateFile(private val path: Path) : Keychain {
    override val name = path.toString()
    override suspend fun read() = path.readTextOrNull()
    override suspend fun write(token: String) = writeAtomically(path, token, private = true)
    override suspend fun delete() = SystemFileSystem.delete(path, mustExist = false)
}
