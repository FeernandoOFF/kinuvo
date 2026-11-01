package kinuvo.harness.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kinuvo.client.WebPush
import kinuvo.harness.core.Notification
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.PushMessage
import kinuvo.protocol.PushSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger("kinuvo.push")

internal actual fun devicePush(peers: PeerRegistry): DevicePush? = if (peers.pushable) WebPushDevices(peers) else null

internal class WebPushDevices(private val peers: PeerRegistry, private val http: HttpClient = HttpClient(CIO) {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 15_000 }
}) : DevicePush {
    override suspend fun send(notification: Notification) = coroutineScope {
        val message = notification.message()
        for (subscription in peers.pushSubscriptions()) launch {
            try { deliver(subscription, message, notification.urgent) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.warn { "Push to ${subscription.endpoint.substringBefore("/v1/")} failed: ${e.message}" } }
        }
    }

    private suspend fun deliver(subscription: PushSubscription, message: ByteArray, urgent: Boolean) {
        val body = WebPush.encrypt(message, WebPush.base64(subscription.p256dh), WebPush.base64(subscription.auth))
        val response = http.post(subscription.endpoint) {
            header("Content-Encoding", "aes128gcm")
            header("Content-Type", "application/octet-stream")
            header("TTL", "86400")
            header("Urgency", if (urgent) "high" else "normal")
            setBody(body)
        }
        when (response.status) {
            HttpStatusCode.NotFound, HttpStatusCode.Gone -> peers.dropPush(subscription.endpoint)
            else -> check(response.status.value in 200..299) { "HTTP ${response.status.value}" }
        }
    }

    override fun close() = http.close()
}

/** Keeps the encrypted record within APNs' and FCM's 4 KiB payloads once base64-encoded. */
internal fun Notification.message(): ByteArray {
    var body = body
    while (true) {
        val bytes = ProtocolJson.encodeToString(PushMessage.serializer(), PushMessage(title.take(200), body, agentId, sessionId, urgent)).encodeToByteArray()
        if (bytes.size <= MAX_PUSH_BYTES || body.isEmpty()) return bytes
        body = if (body.length < 8) "" else body.take(body.length * 3 / 4).trimEnd() + "…"
    }
}

internal const val MAX_PUSH_BYTES = 2700
