package kinuvo.client

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Web Push message encryption (RFC 8291, `aes128gcm` from RFC 8188), single record. */
object WebPush {
    private const val RECORD_SIZE = 4096
    private val random = SecureRandom()
    private val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
    fun newAuthSecret() = ByteArray(16).also(random::nextBytes)

    fun encrypt(plaintext: ByteArray, uaPublic: ByteArray, authSecret: ByteArray,
                server: KeyPair = newKeyPair(), salt: ByteArray = ByteArray(16).also(random::nextBytes)): ByteArray {
        val asPublic = encode(server.public as ECPublicKey)
        val (cek, nonce) = keys(ecdh(server.private as ECPrivateKey, uaPublic), authSecret, uaPublic, asPublic, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }
        val sealed = cipher.doFinal(plaintext + 2)
        return ByteBuffer.allocate(21 + asPublic.size + sealed.size)
            .put(salt).putInt(RECORD_SIZE).put(asPublic.size.toByte()).put(asPublic).put(sealed).array()
    }

    fun decrypt(message: ByteArray, ua: KeyPair, authSecret: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(message)
        val salt = ByteArray(16).also(buffer::get)
        buffer.int
        val asPublic = ByteArray(buffer.get().toInt() and 0xff).also(buffer::get)
        val sealed = ByteArray(buffer.remaining()).also(buffer::get)
        val (cek, nonce) = keys(ecdh(ua.private as ECPrivateKey, asPublic), authSecret, encode(ua.public as ECPublicKey), asPublic, salt)
        val padded = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(sealed)
        val end = padded.indexOfLast { it != 0.toByte() }
        require(end >= 0 && padded[end] == 2.toByte()) { "Not a final Web Push record" }
        return padded.copyOf(end)
    }

    /** The uncompressed P-256 point (65 bytes) Web Push uses as `p256dh`. */
    fun encode(key: ECPublicKey): ByteArray = byteArrayOf(4) + key.w.affineX.fixed() + key.w.affineY.fixed()
    fun publicKey(encoded: ByteArray): ECPublicKey {
        require(encoded.size == 65 && encoded[0] == 4.toByte()) { "Expected an uncompressed P-256 key" }
        val point = ECPoint(BigInteger(1, encoded.copyOfRange(1, 33)), BigInteger(1, encoded.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve)) as ECPublicKey
    }
    fun keyPair(privateKey: ByteArray, publicKey: ByteArray) = KeyPair(publicKey(publicKey),
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, privateKey), curve)))
    fun privateBytes(keys: KeyPair) = (keys.private as ECPrivateKey).s.fixed()

    fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun base64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))

    private fun keys(ecdh: ByteArray, authSecret: ByteArray, uaPublic: ByteArray, asPublic: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val ikm = hmac(hmac(authSecret, ecdh), "WebPush: info\u0000".toByteArray() + uaPublic + asPublic + 1)
        val prk = hmac(salt, ikm)
        return hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray() + 1).copyOf(16) to
            hmac(prk, "Content-Encoding: nonce\u0000".toByteArray() + 1).copyOf(12)
    }
    private fun ecdh(own: ECPrivateKey, other: ByteArray) =
        KeyAgreement.getInstance("ECDH").apply { init(own); doPhase(publicKey(other), true) }.generateSecret()
    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    private fun BigInteger.fixed() = toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
}
