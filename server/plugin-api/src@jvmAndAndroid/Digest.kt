package kinuvo.server.plugin

import java.security.MessageDigest

actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
