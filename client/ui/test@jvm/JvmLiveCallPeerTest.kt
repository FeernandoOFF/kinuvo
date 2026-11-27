package kinuvo.ui.session

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kinuvo.app.session.LiveCallException
import kinuvo.protocol.voice.LivePeerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmLiveCallPeerTest {
    private class Headless {
        val module = HeadlessAudioDeviceModule()
        val factory = PeerConnectionFactory(module)
        val source = CustomAudioSource()
        fun audio() = PeerAudio(factory, source) { source.dispose(); factory.dispose(); module.dispose() }
        fun speak(frames: Int) {
            repeat(frames) { n ->
                val pcm = ByteArray(960)
                for (i in 0 until 480) {
                    val sample = (sin(2 * PI * 440 * (n * 480 + i) / 48_000) * 12_000).toInt().toShort().toInt()
                    pcm[i * 2] = sample.toByte(); pcm[i * 2 + 1] = (sample shr 8).toByte()
                }
                source.pushAudio(pcm, 16, 48_000, 1, 480)
                Thread.sleep(10)
            }
        }
    }

    private class Answerer {
        val microphone = Headless()
        val heard = AtomicLong()
        val heardLoud = AtomicLong()
        @Volatile private var remoteTrack: AudioTrack? = null
        @Volatile private var remoteTransceiver: RTCRtpTransceiver? = null
        private val gathered = CompletableDeferred<Unit>()
        private val track = microphone.factory.createAudioTrack("remote", microphone.source)
        private val connection = microphone.factory.createPeerConnection(RTCConfiguration().apply { bundlePolicy = RTCBundlePolicy.MAX_BUNDLE }, object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {}
            override fun onIceGatheringChange(state: RTCIceGatheringState) { if (state == RTCIceGatheringState.COMPLETE) gathered.complete(Unit) }
            override fun onTrack(transceiver: RTCRtpTransceiver) {
                (transceiver.receiver.track as? AudioTrack)?.also { remoteTrack = it; remoteTransceiver = transceiver }?.addSink { data, _, _, _, _ ->
                    heard.addAndGet(data.size.toLong())
                    heardLoud.addAndGet(data.count { it.toInt() != 0 }.toLong())
                }
            }
        })

        private val sender = connection.addTrack(track, listOf("remote"))

        suspend fun answer(offer: String): String {
            suspendCancellableCoroutine { c ->
                connection.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, offer), object : SetSessionDescriptionObserver {
                    override fun onSuccess() = c.resume(Unit)
                    override fun onFailure(error: String?) = c.resumeWithException(IllegalStateException(error))
                })
            }
            val answer = suspendCancellableCoroutine { c ->
                connection.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) = c.resume(description)
                    override fun onFailure(error: String?) = c.resumeWithException(IllegalStateException(error))
                })
            }
            suspendCancellableCoroutine { c ->
                connection.setLocalDescription(answer, object : SetSessionDescriptionObserver {
                    override fun onSuccess() = c.resume(Unit)
                    override fun onFailure(error: String?) = c.resumeWithException(IllegalStateException(error))
                })
            }
            withTimeout(10_000) { gathered.await() }
            return connection.localDescription.sdp
        }

        fun close() {
            remoteTrack?.dispose(); remoteTransceiver?.dispose(); connection.close(); sender.dispose(); track.dispose(); microphone.source.dispose(); microphone.factory.dispose(); microphone.module.dispose()
        }
    }

    @Test fun offerIsCompleteMaxBundleAudioOnly() = runBlocking<Unit> {
        val device = Headless()
        val peer = JvmLiveCallPeer { device.audio() }
        try {
            val offer = withTimeout(20_000) { peer.createOffer() }
            assertTrue("a=group:BUNDLE" in offer, offer)
            assertEquals(1, Regex("^m=", RegexOption.MULTILINE).findAll(offer).count(), offer)
            assertTrue("m=audio" in offer && "a=sendrecv" in offer, offer)
            assertTrue("m=application" !in offer, offer)
            assertTrue("a=candidate" in offer, "ICE gathering must be complete before the offer is sent")
            assertTrue("a=end-of-candidates" in offer || "a=candidate" in offer)
            assertEquals(LivePeerState.Connecting, peer.state.value)
        } finally { peer.close() }
    }

    @Test fun connectsCarriesAudioBothWaysAndMutesAtOnce() = runBlocking<Unit> {
        val device = Headless()
        val remote = Answerer()
        val peer = JvmLiveCallPeer { device.audio() }
        try {
            withTimeout(30_000) {
                val answer = remote.answer(peer.createOffer())
                peer.accept(answer)
                peer.state.first { it == LivePeerState.Connected }
                val speaking = launch(Dispatchers.IO) { while (isActive) { device.speak(20); remote.microphone.speak(20) } }
                try {
                    while (remote.heardLoud.get() < 4_000) delay(50)
                    withTimeout(5_000) { while (peer.levels.value.input <= 0f || peer.levels.value.output <= 0f) delay(50) }
                    peer.setMuted(true)
                    delay(500)
                    val before = remote.heardLoud.get()
                    delay(1_000)
                    val during = remote.heardLoud.get() - before
                    peer.setMuted(false)
                    delay(500)
                    val after = remote.heardLoud.get()
                    delay(1_000)
                    val resumed = remote.heardLoud.get() - after
                    delay(500)
                    assertTrue(during < resumed / 4, "Muted capture still reached the other side: $during vs $resumed")
                } finally { speaking.cancel() }
            }
            peer.close()
            assertEquals(LivePeerState.Closed, peer.state.value)
            peer.close()
        } finally { peer.close(); remote.close() }
    }

    @Test fun missingDevicesAndBadAnswersAreExplained() = runBlocking<Unit> {
        val none = JvmLiveCallPeer { throw LiveCallException("No microphone found. Connect one and try again.") }
        assertEquals("No microphone found. Connect one and try again.", assertFailsWith<LiveCallException> { none.createOffer() }.message)
        none.close()

        val device = Headless()
        val peer = JvmLiveCallPeer { device.audio() }
        try {
            withTimeout(20_000) { peer.createOffer() }
            assertFailsWith<LiveCallException> { peer.accept("not sdp") }
        } finally { peer.close() }
        assertFailsWith<LiveCallException> { peer.accept("v=0") }
    }
}
