package kinuvo.ui

import kinuvo.app.connection.*

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidCredentials(context: Context) : DeviceCredentials {
    private val dir = File(context.noBackupFilesDir, "device-credentials")
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Device credential key is unavailable; pair this Device again" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun file(server: String) = File(dir, MessageDigest.getInstance("SHA-256").digest(server.toByteArray()).joinToString("") { "%02x".format(it) })
    @Synchronized
    override fun read(server: String): String? {
        val file = file(server)
        if (!file.isFile) return null
        val data = file.readBytes()
        require(data.size >= 28) { "Invalid Device credential" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            updateAAD(server.toByteArray())
            doFinal(data.copyOfRange(12, data.size)).decodeToString()
        }
    }
    @Synchronized
    override fun write(server: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)); updateAAD(server.toByteArray()) }
        val data = cipher.doFinal(token.toByteArray())
        dir.mkdirs()
        val temp = File.createTempFile("device", ".tmp", dir)
        try {
            temp.writeBytes(cipher.iv + data)
            check(temp.renameTo(file(server))) { "Couldn't save the Device credential" }
        } finally { temp.delete() }
    }
    override fun delete(server: String) { val file = file(server); check(!file.exists() || file.delete()) { "Couldn't delete the Device credential" } }
    private companion object { const val ALIAS = "dev.kinuvo.device-credentials" }
}
