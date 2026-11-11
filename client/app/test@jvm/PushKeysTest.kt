package kinuvo.app.connection

import kinuvo.protocol.push.WebPush
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.push.PushMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class Prefs : Preferences {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    override fun clear() = values.clear()
}

class PushKeysTest {
    private val prefs = Prefs()
    private val keys = PushKeys(prefs)

    private fun push(message: PushMessage, keys: PushKeys = this.keys): String {
        val subscription = keys.subscription("https://relay/fcm/token")
        val json = ProtocolJson.encodeToString(PushMessage.serializer(), message)
        return WebPush.base64(WebPush.encrypt(json.toByteArray(), WebPush.base64(subscription.p256dh), WebPush.base64(subscription.auth)))
    }

    @Test fun `opens a Notification sealed to its subscription`() {
        val message = PushMessage("Ada needs an Approval", "shell: rm -rf build", "ada", "s1", urgent = true)
        assertEquals(message, keys.open(push(message)))
        assertEquals("kinuvo://chat/ada/s1", message.link)
        assertEquals("kinuvo://chat/ada/s1?server=home", message.copy(serverId = "home").link)
    }

    @Test fun `keeps its keys across launches`() {
        assertEquals(keys.subscription("e"), PushKeys(prefs).subscription("e"))
        assertEquals("Done", PushKeys(prefs).open(push(PushMessage("Done", "")))?.title)
    }

    @Test fun `drops what it can't open`() {
        assertNull(keys.open(push(PushMessage("x", ""), PushKeys(Prefs()))))
        assertNull(keys.open("not a record"))
        assertNull(PushMessage("x", "").link)
    }
}
