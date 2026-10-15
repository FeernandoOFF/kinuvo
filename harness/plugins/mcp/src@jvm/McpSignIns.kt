package kinuvo.harness.mcp

import kinuvo.harness.core.Keychain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MCP sign-ins, one file per server under [dir], encrypted with a key kept in the OS keychain (whose entries are too
 * small for tokens). Without a keychain they are plain JSON readable only by the user.
 */
class McpSignIns(private val dir: File, private val keychain: Keychain? = Keychain.system("kinuvo", "mcp-oauth")) : McpSignInStore {
    private val mutex = Mutex()
    private var key: SecretKeySpec? = null
    private var keyless = false

    override suspend fun get(id: String): McpSignIn? = mutex.withLock {
        val encrypted = file(id, ENCRYPTED)
        val plain = file(id, PLAIN)
        val text = when {
            encrypted.exists() -> key()?.let { k -> runCatching { decrypt(k, encrypted.readText()) }.getOrNull() }
            plain.exists() -> plain.readText()
            else -> null
        }
        text?.let { runCatching { json.decodeFromString(McpSignIn.serializer(), it) }.getOrNull() }
    }

    override suspend fun put(id: String, signIn: McpSignIn): Unit = mutex.withLock {
        val text = json.encodeToString(McpSignIn.serializer(), signIn)
        val k = key()
        withContext(Dispatchers.IO) {
            if (k != null) write(file(id, ENCRYPTED), encrypt(k, text), mustBePrivate = false) else write(file(id, PLAIN), text, mustBePrivate = true)
            file(id, if (k != null) PLAIN else ENCRYPTED).delete()
        }
    }

    override suspend fun remove(id: String): Unit = mutex.withLock {
        file(id, ENCRYPTED).delete()
        file(id, PLAIN).delete()
    }

    override suspend fun removeAll(prefix: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) { File(dir, prefix.split('/').also(::checkSignInId).joinToString(File.separator)).deleteRecursively() }
    }

    /** The keychain's key, created on first use; null (and a warning, once) when there's no keychain to keep it in. */
    private suspend fun key(): SecretKeySpec? {
        key?.let { return it }
        if (keyless) return null
        val k = keychain?.let { chain ->
            chain.read() ?: Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes)).also { chain.write(it) }
        }
        if (k == null) {
            keyless = true
            System.err.println("MCP sign-ins: no keychain, keeping them unencrypted in $dir (owner-only)")
            return null
        }
        return SecretKeySpec(Base64.getDecoder().decode(k), "AES").also { key = it }
    }

    private fun file(id: String, ext: String) = File(dir, id.split('/').also(::checkSignInId).joinToString(File.separator) + ext)

    /** Writes [text] atomically, owner-only where the filesystem allows; [mustBePrivate] refuses to write otherwise. */
    private fun write(file: File, text: String, mustBePrivate: Boolean) {
        file.parentFile.mkdirs()
        val dirsPrivate = generateSequence(file.parentFile) { it.parentFile }.takeWhile { it.startsWith(dir) }.toList().map { restrict(it) }.all { it }
        val tmp = File(file.parentFile, "${file.name}.tmp").toPath()
        Files.deleteIfExists(tmp)
        // Created owner-only, so the token is never readable by others, even briefly.
        val private = runCatching { Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_PERMS))) }.isSuccess
        check(!mustBePrivate || (private && dirsPrivate)) { "Can't make $dir readable only by you, so MCP sign-ins can't be kept unencrypted here" }
        if (!private) Files.createFile(tmp)
        Files.writeString(tmp, text)
        Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun restrict(dir: File) = runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString(DIR_PERMS)) }.isSuccess

    private fun encrypt(key: SecretKeySpec, text: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray()))
    }

    private fun decrypt(key: SecretKeySpec, text: String): String {
        val bytes = Base64.getDecoder().decode(text.trim())
        val cipher = Cipher.getInstance(AES).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }

    private companion object {
        const val AES = "AES/GCM/NoPadding"
        const val ENCRYPTED = ".enc"
        const val PLAIN = ".json"
        const val DIR_PERMS = "rwx------"
        const val FILE_PERMS = "rw-------"
        val random = SecureRandom()
        val json = Json { ignoreUnknownKeys = true }
    }
}
