package kinuvo.harness.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kinuvo.client.WebPush
import kinuvo.harness.core.plugin.Notification
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.PushMessage
import kinuvo.protocol.PushSubscription
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.interfaces.ECPublicKey
import kotlin.test.*

class DevicePushTest {
    private val auth = DeviceAuth(Files.createTempDirectory("device-push").resolve("devices.json").toFile())
    private val keys = WebPush.newKeyPair()
    private val secret = WebPush.newAuthSecret()

    private fun pair(name: String) = assertNotNull(auth.authenticate(name, null, auth.currentPairingCode()))
    private fun subscription(endpoint: String) = PushSubscription(endpoint, WebPush.base64(WebPush.encode(keys.public as ECPublicKey)), WebPush.base64(secret))

    @Test fun `subscribed Devices get an encrypted Notification`() = runTest {
        auth.setPush(pair("phone"), subscription("https://relay.test/v1/fcm/a"))
        pair("laptop")
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val push = WebPushDevices(auth, HttpClient(MockEngine { request ->
            sent += request.url.toString() to (request.body as OutgoingContent.ByteArrayContent).bytes()
            assertEquals("aes128gcm", request.headers["Content-Encoding"])
            assertEquals("high", request.headers["Urgency"])
            respond("", HttpStatusCode.Created)
        }))
        push.send(Notification("Kinuvo needs an Approval", "shell: rm -rf build", "agent", "session", urgent = true))
        val (url, body) = sent.single()
        assertEquals("https://relay.test/v1/fcm/a", url)
        val message = ProtocolJson.decodeFromString(PushMessage.serializer(), WebPush.decrypt(body, keys, secret).decodeToString())
        assertEquals(PushMessage("Kinuvo needs an Approval", "shell: rm -rf build", "agent", "session", true), message)
    }

    @Test fun `a gone endpoint is forgotten`() = runTest {
        auth.setPush(pair("phone"), subscription("https://relay.test/v1/apns/production/b"))
        WebPushDevices(auth, HttpClient(MockEngine { respond("", HttpStatusCode.Gone) })).send(Notification("t", "b"))
        assertEquals(emptyList(), auth.pushSubscriptions())
    }

    @Test fun `endpoints must be https and belong to one Device`() {
        val phone = pair("phone")
        assertFailsWith<IllegalArgumentException> { auth.setPush(phone, subscription("http://relay.test/x")) }
        auth.setPush(phone, subscription("https://relay.test/x"))
        auth.setPush(pair("reinstalled"), subscription("https://relay.test/x"))
        assertEquals(listOf("reinstalled"), auth.devices().filter { it.push != null }.map { it.name })
        auth.setPush(phone, null)
        assertEquals(1, auth.pushSubscriptions().size)
    }

    @Test fun `long bodies are clipped to fit a push`() {
        val bytes = Notification("t", "x".repeat(10_000)).message()
        assertTrue(bytes.size <= MAX_PUSH_BYTES)
        assertTrue(ProtocolJson.decodeFromString(PushMessage.serializer(), bytes.decodeToString()).body.endsWith("…"))
    }
}
