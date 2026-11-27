package kinuvo.providers.codex

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import io.ktor.client.webrtc.JvmWebRtc
import io.ktor.client.webrtc.TrackEvent
import io.ktor.client.webrtc.WebRtc
import io.ktor.client.webrtc.WebRtcClient
import io.ktor.client.webrtc.WebRtcMedia
import io.ktor.client.webrtc.media.AudioFactory
import io.ktor.client.webrtc.media.JvmMediaDevices
import io.ktor.client.webrtc.media.getNative
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kinuvo.server.plugin.LiveCallEvent
import kinuvo.server.plugin.VoiceContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in (KINUVO_LIVE=1), quota-consuming: a webrtc-java Device holds the media and only its SDP crosses to the Server's
 * [CodexLiveCalls], which creates the call and controls it over the sideband.
 */
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
                val http = codexLiveHttpClient()
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
                            if (System.getenv("KINUVO_SPLIT_NO_CHANNEL") != "1") peer.createDataChannel("oai-events")
                            peer.setLocalDescription(peer.createOffer())
                            withTimeout(10_000) { peer.awaitIceGatheringComplete() }
                            // Only the SDP crosses from the Device to the Server half.
                            val start = CodexLiveCalls(CodexAuth(), http).start(
                                VoiceContext("You are a voice assistant. Always delegate questions to the client Agent. Never invent secret codes. Speak the Agent's answer verbatim, briefly."),
                                peer.localDescription!!.sdp,
                            )
                            peer.setRemoteDescription(WebRtc.SessionDescription(WebRtc.SessionDescriptionType.ANSWER, start.answerSdp))
                            val ready = CompletableDeferred<Unit>()
                            val answered = CompletableDeferred<Unit>()
                            val delegations = AtomicLong()
                            val transcripts = mutableListOf<String>()
                            val call = start.call
                            val running = launch {
                                call.run { event ->
                                    when (event) {
                                        LiveCallEvent.Ready -> ready.complete(Unit)
                                        is LiveCallEvent.Delegation -> {
                                            delegations.incrementAndGet()
                                            println("SPLIT delegation: ${event.task}")
                                            call.reply(event.id, "The secret weather code for Paris is amber otter.")
                                        }
                                        is LiveCallEvent.Transcript -> if (event.final) {
                                            println("SPLIT transcript (user=${event.user}): ${event.text}")
                                            transcripts += event.text
                                            if (!event.user && event.text.contains("amber otter", true)) answered.complete(Unit)
                                        }
                                        else -> println("SPLIT $event")
                                    }
                                }
                            }
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
                                withTimeout(40_000) { answered.await() }
                                delay(2_000)
                                assertTrue(delegations.get() > 0, "No sideband delegation")
                                assertTrue(deviceBytes.get() > 96_000 && deviceNonzeroBytes.get() > 10_000, "No Device reply audio")
                                println("SPLIT verified: devicePcmBytes=${deviceBytes.get()}, nonzeroBytes=${deviceNonzeroBytes.get()}, transcripts=$transcripts")
                                call.pause()
                                delay(300)
                                call.resume()
                                call.notify("Notify the user now: the violet lantern is ready. Say that aloud briefly.")
                                delay(4_000)
                                call.end()
                                withTimeout(10_000) { running.join() }
                            } finally { pump.cancelAndJoin(); running.cancelAndJoin(); sink.cancelAndJoin() }
                        }
                    } finally { peer.close() }
                } finally { rtc.close(); audio.close(); http.close() }
            }
        }
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
