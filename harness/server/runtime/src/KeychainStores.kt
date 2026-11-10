package kinuvo.harness.core

import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.readTextOrNull
import kinuvo.server.plugin.writeAtomically
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The host's keychain, or null when it has none (e.g. headless Linux or a container). */
fun Keychain.Companion.system(service: String, account: String): Keychain? = systemKeychain(service, account)

internal expect fun systemKeychain(service: String, account: String): Keychain?

/** A token in a file only its owner can read. */
class PrivateFile(private val path: Path) : Keychain {
    override val name = path.toString()
    override suspend fun read() = path.readTextOrNull()
    override suspend fun write(token: String) = writeAtomically(path, token, private = true)
    override suspend fun delete() = SystemFileSystem.delete(path, mustExist = false)
}
