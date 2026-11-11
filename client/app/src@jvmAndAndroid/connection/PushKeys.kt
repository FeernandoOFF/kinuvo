package kinuvo.app.connection

import kinuvo.protocol.WebPush
import kinuvo.protocol.Links
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.PushMessage
import kinuvo.protocol.PushSubscription
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

/** This install's Web Push keys (RFC 8291), made once and kept in [prefs]; Servers encrypt Notifications to them. */
class PushKeys(prefs: Preferences) {
    private val keys: KeyPair
    private val auth: ByteArray

    init {
        val saved = prefs[KEY]?.split('.')?.takeIf { it.size == 3 }
        if (saved != null) {
            keys = WebPush.keyPair(WebPush.base64(saved[0]), WebPush.base64(saved[1]))
            auth = WebPush.base64(saved[2])
        } else {
            keys = WebPush.newKeyPair()
            auth = WebPush.newAuthSecret()
            prefs[KEY] = listOf(WebPush.privateBytes(keys), WebPush.encode(keys.public as ECPublicKey), auth).joinToString(".") { WebPush.base64(it) }
        }
    }

    fun subscription(endpoint: String) = PushSubscription(endpoint, WebPush.base64(WebPush.encode(keys.public as ECPublicKey)), WebPush.base64(auth))

    /** The Notification in a pushed record (base64url); null when it isn't one for this install. */
    fun open(record: String): PushMessage? = try {
        ProtocolJson.decodeFromString(PushMessage.serializer(), WebPush.decrypt(WebPush.base64(record), keys, auth).decodeToString())
    } catch (_: Exception) { null }

    private companion object {
        const val KEY = "push.keys"
    }
}

/** The Session a pushed Notification opens, on the Server that sent it, or null for the app itself. */
val PushMessage.link get() = agentId?.let { Links.chat(it, sessionId, serverId) }
