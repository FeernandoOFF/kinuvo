package kinuvo.server.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SealedFileKeychainTest {
    private val dir = Files.createTempDirectory("sealed").toFile().also { it.deleteOnExit() }
    private val file = File(dir, "token.enc")

    private class AesSealer(var key: SecretKeySpec = newKey()) : Sealer {
        override fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + cipher.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }

        companion object {
            fun newKey() = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        }
    }

    private fun keychain(sealer: Sealer) = SealedFileKeychain(Path(file.path), sealer, "Test")

    @Test
    fun roundTripsThroughAFreshInstanceWithoutPlaintextOnDisk() = runBlocking {
        val sealer = AesSealer()
        keychain(sealer).write("secret-token")
        assertEquals("secret-token", keychain(sealer).read())
        assertFalse("secret-token" in file.readText())
    }

    @Test
    fun usesANewIvForEveryWrite() = runBlocking {
        val chain = keychain(AesSealer())
        chain.write("same")
        val first = file.readText()
        chain.write("same")
        assertFalse(first == file.readText())
    }

    @Test
    fun anUnreadableEntryReadsAsAbsentAndTheNextWriteReplacesIt() = runBlocking {
        val sealer = AesSealer()
        keychain(sealer).write("old")
        sealer.key = AesSealer.newKey()
        assertNull(keychain(sealer).read())
        file.writeText("not base64 !!")
        assertNull(keychain(sealer).read())
        keychain(sealer).write("new")
        assertEquals("new", keychain(sealer).read())
    }

    @Test
    fun deleteRemovesTheEntry() = runBlocking {
        val chain = keychain(AesSealer())
        chain.write("x")
        chain.delete()
        assertNull(chain.read())
    }
}
