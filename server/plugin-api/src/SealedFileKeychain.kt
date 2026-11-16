package kinuvo.server.plugin

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Encrypts and decrypts with a key that never leaves the platform; [open] throws when [sealed] can't be decrypted. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** A token in a file encrypted by [sealer]; one that can't be decrypted (a lost or changed key) reads as absent, and the next write replaces it. */
@OptIn(ExperimentalEncodingApi::class)
class SealedFileKeychain(private val path: Path, private val sealer: Sealer, override val name: String) : Keychain {
    override suspend fun read(): String? = path.readTextOrNull()?.let { text ->
        runCatching { sealer.open(Base64.decode(text.trim())).decodeToString() }.getOrNull()
    }

    override suspend fun write(token: String) = writeAtomically(path, Base64.encode(sealer.seal(token.encodeToByteArray())), private = true)

    override suspend fun delete() = SystemFileSystem.delete(path, mustExist = false)
}
