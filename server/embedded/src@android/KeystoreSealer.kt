package kinuvo.server.embedded

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kinuvo.server.plugin.Sealer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a non-exportable key in the Android Keystore, and a random IV kept in front of each ciphertext. */
internal class KeystoreSealer(private val alias: String) : Sealer {
    override fun seal(plain: ByteArray): ByteArray = try {
        encrypt(key(), plain)
    } catch (_: Exception) {
        // After a restore or a lock screen change the key can be unusable, and so is anything sealed with it: start over.
        keyStore().deleteEntry(alias)
        encrypt(key(), plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, existing() ?: error("No key"), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)) }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existing() = keyStore().getKey(alias, null) as? SecretKey

    private fun key(): SecretKey = existing() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
