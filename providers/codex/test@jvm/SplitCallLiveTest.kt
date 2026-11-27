package kinuvo.providers.codex

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.webrtc.JvmWebRtc
import io.ktor.client.webrtc.TrackEvent
import io.ktor.client.webrtc.WebRtc
import io.ktor.client.webrtc.WebRtcClient
import io.ktor.client.webrtc.WebRtcMedia
import io.ktor.client.webrtc.media.AudioFactory
import io.ktor.client.webrtc.media.JvmMediaDevices
import io.ktor.client.webrtc.media.getNative
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Opt-in quota-consuming architecture spike; the Device never reads or writes oai-events. */
@OptIn(ExperimentalKtorApi::class)
class SplitCallLiveTest {
    @Test
    fun deviceMediaWithServerSideband() {
        if (System.getenv("KINUVO_LIVE") != "1") return
        val speech = say("Ask the agent what the secret weather code for Paris is today.")
        runBlocking {
            withTimeout(70_000) {
                val audio = DeviceAudio()
                val rtc = WebRtcClient(JvmWebRtc) {
                    mediaTrackFactory = JvmMediaDevices(audioFactory = audio)
                    defaultConnectionConfig = { bundlePolicy = WebRtc.BundlePolicy.MAX_BUNDLE }
                }
                try {
                    val peer = rtc.createPeerConnection()
                    try {
                        coroutineScope {
                            val deviceBytes = AtomicLong()
                            val deviceNonzeroBytes = AtomicLong()
                            val sink = launch {
                                val track = peer.trackEvents.filterIsInstance<TrackEvent.Add>().first().track as WebRtcMedia.AudioTrack
                                track.getNative().addSink { data, _, _, _, _ ->
                                    deviceBytes.addAndGet(data.size.toLong())
                                    deviceNonzeroBytes.addAndGet(data.count { it != 0.toByte() }.toLong())
                                }
                            }
                            peer.addTrack(rtc.createAudioTrack {})
                            val omitChannel = System.getenv("KINUVO_SPLIT_NO_CHANNEL") == "1"
                            if (!omitChannel) peer.createDataChannel("oai-events")
                            peer.setLocalDescription(peer.createOffer())
                            withTimeout(10_000) { peer.awaitIceGatheringComplete() }
                            // Only the SDP crosses from the Device to this HTTP/WS-only Server half.
                            val server = SidebandServer()
                            try {
                                val call = server.create(peer.localDescription!!.sdp)
                                peer.setRemoteDescription(WebRtc.SessionDescription(WebRtc.SessionDescriptionType.ANSWER, call.answer))
                                val ready = CompletableDeferred<Unit>()
                                val answer = CompletableDeferred<Unit>()
                                val closed = CompletableDeferred<Unit>()
                                val sideband = launch { server.observe(call.id, ready, answer, closed) }
                                withTimeout(15_000) {
                                    ready.await()
                                    peer.state.first { it == WebRtc.ConnectionState.CONNECTED }
                                }
                                val pump = launch {
                                    var at = -24_000 * 2
                                    while (true) {
                                        val frame = if (at >= 0 && at + 480 <= speech.size) speech.copyOfRange(at, at + 480) else ByteArray(480)
                                        audio.source.pushAudio(frame, 16, 24_000, 1, 240)
                                        at += 480
                                        delay(10)
                                    }
                                }
                                try {
                                    withTimeout(40_000) { answer.await() }
                                    delay(2_000)
                                    assertTrue(server.delegations > 0, "No sideband delegation")
                                    assertTrue(deviceBytes.get() > 96_000 && deviceNonzeroBytes.get() > 10_000, "No Device reply audio")
                                    println("SPLIT verified: noChannel=$omitChannel, devicePcmBytes=${deviceBytes.get()}, nonzeroBytes=${deviceNonzeroBytes.get()}, sidebandBytes=${server.bytes}, elapsedMs=${server.elapsedMs}, audioBase64Chars=${server.audioChars}, eventCounts=${server.counts}, eventBytes=${server.eventBytes}")
                                    // A tiny best-effort notification probe, then end through the sideband.
                                    server.notify.complete(Unit)
                                    withTimeout(10_000) { closed.await() }
                                } finally { pump.cancelAndJoin(); sideband.cancelAndJoin() }
                            } finally { server.close(); sink.cancelAndJoin() }
                        }
                    } finally { peer.close() }
                } finally { rtc.close(); audio.close() }
            }
        }
    }

    private data class Call(val answer: String, val id: String)

    /** Deliberately has no peer, media track or native WebRTC dependency. */
    private class SidebandServer {
        private val http = HttpClient(codexEngine()) { install(WebSockets) }
        private lateinit var tokens: CodexTokens
        private val session = Uuid.random().toString()
        val notify = CompletableDeferred<Unit>()
        val counts = mutableMapOf<String, Int>()
        val eventBytes = mutableMapOf<String, Long>()
        private var connectedAt = 0L
        val elapsedMs get() = (System.nanoTime() - connectedAt) / 1_000_000
        var bytes = 0L
        var audioChars = 0L
        var delegations = 0

        suspend fun create(offer: String): Call {
            tokens = CodexAuth().tokens()
            val response = http.post("https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas") {
                header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
                header("chatgpt-account-id", tokens.accountId)
                header("OpenAI-Alpha", "quicksilver=v2")
                header("originator", "kinuvo")
                header("session-id", session)
                header("x-session-id", session)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("sdp", offer)
                    put("session", buildJsonObject {
                        put("model", "gpt-live-1-codex")
                        put("instructions", "You are a voice assistant. Always delegate questions to the client Agent. Never invent secret codes. Speak the Agent's answer verbatim, briefly.")
                        put("audio", buildJsonObject { put("output", buildJsonObject { put("voice", "cove") }) })
                        put("delegation", buildJsonObject { put("type", "client") })
                    })
                }.toString())
            }
            check(response.status.value == 201) { "Call creation HTTP ${response.status.value}: ${response.bodyAsText().take(500)}" }
            val id = requireNotNull(response.headers[HttpHeaders.Location]).substringBefore('?').trimEnd('/').substringAfterLast('/')
            println("SPLIT call HTTP 201; attestation omitted")
            return Call(response.bodyAsText(), id)
        }

        suspend fun observe(id: String, ready: CompletableDeferred<Unit>, answer: CompletableDeferred<Unit>, closed: CompletableDeferred<Unit>) {
            http.webSocket("wss://api.openai.com/v1/live/$id", request = {
                header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
                header("chatgpt-account-id", tokens.accountId)
                header("OpenAI-Alpha", "quicksilver=v2")
                header("originator", "kinuvo")
                header("session-id", session)
                header("x-session-id", session)
            }) {
                println("SPLIT sideband connected; no session.update or response.create")
                connectedAt = System.nanoTime()
                ready.complete(Unit)
                val controls = launch {
                    notify.await()
                    send(Frame.Text("""{"type":"input_audio.pause"}"""))
                    delay(300)
                    send(Frame.Text("""{"type":"input_audio.resume"}"""))
                    send(Frame.Text(buildJsonObject {
                        put("type", "session.context.append")
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", "Notify the user now: the violet lantern is ready. Say that aloud briefly.") }) })
                    }.toString()))
                    delay(4_000)
                    send(Frame.Text("""{"type":"session.close"}"""))
                    println("SPLIT sent session.close")
                }
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val raw = frame.readText()
                        bytes += raw.toByteArray().size
                        val event = Json.parseToJsonElement(raw).jsonObject
                        val type = event.text("type")
                        counts[type] = (counts[type] ?: 0) + 1
                        eventBytes[type] = (eventBytes[type] ?: 0) + raw.toByteArray().size
                        if (counts[type] == 1 && type.contains("audio")) println("SPLIT $type keys=${event.keys}")
                        audioChars += event.text("audio").length
                        when (type) {
                            "delegation.created" -> {
                                delegations++
                                val item = event.getValue("item").jsonObject
                                println("SPLIT sideband delegation received")
                                send(Frame.Text(buildJsonObject {
                                    put("type", "delegation.context.append")
                                    put("delegation_item_id", item.text("id"))
                                    put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", "The secret weather code for Paris is amber otter.") }) })
                                }.toString()))
                            }
                            "output_transcript.added", "turn.done" -> {
                                val text = event["item"]?.jsonObject?.text("text").orEmpty() + event["turn"]?.jsonObject?.text("transcript").orEmpty()
                                println("SPLIT $type: $text")
                                if (text.contains("amber otter", true)) answer.complete(Unit)
                            }
                            "session.input_audio.paused", "session.input_audio.resumed" -> println("SPLIT $type")
                            "error" -> println("SPLIT error: ${event["error"]}")
                            "session.closed" -> { println("SPLIT session.closed"); closed.complete(Unit); break }
                        }
                    }
                    println("SPLIT sideband ended: ${closeReason.await()}")
                    closed.complete(Unit)
                } finally { controls.cancelAndJoin() }
            }
        }

        fun close() = http.close()
    }

    private class DeviceAudio : AudioFactory {
        override val audioModule = HeadlessAudioDeviceModule()
        override val peerConnectionFactory = PeerConnectionFactory(audioModule)
        val source = CustomAudioSource()
        override fun createAudioSource(constraints: WebRtcMedia.AudioTrackConstraints): AudioTrackSource = source
        override fun close() { source.dispose(); peerConnectionFactory.dispose() }
    }

    private fun say(text: String): ByteArray {
        val file = File.createTempFile("split-call", ".wav")
        try {
            check(ProcessBuilder("say", "-o", file.path, "--data-format=LEI16@24000", text).start().waitFor() == 0)
            RandomAccessFile(file, "r").use { wav ->
                var at = 12L
                while (true) {
                    wav.seek(at)
                    val id = ByteArray(4).also { wav.readFully(it) }.decodeToString()
                    val size = Integer.reverseBytes(wav.readInt()).toLong()
                    if (id == "data") return ByteArray(size.toInt()).also { wav.readFully(it) }
                    at += 8 + size + size % 2
                }
            }
        } finally { file.delete() }
        error("Missing WAV data")
    }
}

private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()
