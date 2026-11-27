package kinuvo.ui.session

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import kinuvo.app.session.LiveCallException
import kinuvo.app.session.LiveCallLevels
import kinuvo.app.session.LiveCallPeer
import kinuvo.protocol.voice.LivePeerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The WebRTC stack behind one peer: [source] feeds the microphone track; [close] releases everything. */
class PeerAudio(val factory: PeerConnectionFactory, val source: AudioTrackSource, val close: () -> Unit)

/** The default microphone and speaker through WebRTC's audio device module, with echo cancellation, noise suppression and gain control on. */
fun deviceAudio(): PeerAudio {
    try {
        val module = AudioDeviceModule()
        if (module.recordingDevices.isEmpty()) { module.dispose(); throw LiveCallException("No microphone found. Connect one and try again.") }
        if (module.playoutDevices.isEmpty()) { module.dispose(); throw LiveCallException("No speaker found. Connect one and try again.") }
        val processing = callProcessing()
        val factory = PeerConnectionFactory(module, processing)
        val source = factory.createAudioSource(AudioOptions().apply {
            echoCancellation = true
            noiseSuppression = true
            autoGainControl = true
            highpassFilter = true
        })
        return PeerAudio(factory, source) {
            runCatching { source.dispose() }
            runCatching { factory.dispose() }
            runCatching { module.dispose() }
            runCatching { processing.dispose() }
        }
    } catch (e: LinkageError) {
        throw LiveCallException("Live voice isn't available on this computer.", e)
    }
}

fun callProcessing() = AudioProcessing().apply {
    applyConfig(AudioProcessingConfig().apply {
        echoCanceller.enabled = true
        noiseSuppression.enabled = true
        highPassFilter.enabled = true
        gainController.enabled = true
    })
}

class JvmLiveCallPeer(private val audio: () -> PeerAudio = ::deviceAudio) : LiveCallPeer {
    override val state: StateFlow<LivePeerState>
        field = MutableStateFlow(LivePeerState.Connecting)
    override val levels: StateFlow<LiveCallLevels>
        field = MutableStateFlow(LiveCallLevels())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gathered = CompletableDeferred<Unit>()
    private val lock = Any()
    private var native: Native? = null
    private var closed = false
    @Volatile private var muted = false
    @Volatile private var speakerMuted = false

    private class Native(val audio: PeerAudio, val connection: RTCPeerConnection, val track: AudioTrack, val sender: RTCRtpSender)

    override suspend fun createOffer(): String = withContext(Dispatchers.IO) {
        val stack = audio()
        val connection: RTCPeerConnection
        val track: AudioTrack
        val sender: RTCRtpSender
        try {
            val config = RTCConfiguration().apply { bundlePolicy = RTCBundlePolicy.MAX_BUNDLE }
            connection = stack.factory.createPeerConnection(config, observer)
            track = stack.factory.createAudioTrack("kinuvo-microphone", stack.source)
            sender = connection.addTrack(track, listOf("kinuvo"))
        } catch (e: Throwable) {
            stack.close()
            throw e
        }
        val ours = Native(stack, connection, track, sender)
        synchronized(lock) {
            if (closed) { teardown(ours); throw LiveCallException("The call was closed.") }
            native = ours
        }
        track.isEnabled = !muted
        val offer = suspendCancellableCoroutine { c ->
            connection.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) = c.resume(description)
                override fun onFailure(error: String?) = c.resumeWithException(LiveCallException("Couldn't prepare the call: $error"))
            })
        }
        setLocal(connection, offer)
        try { withTimeout(ICE_TIMEOUT_MILLIS) { gathered.await() } }
        catch (e: TimeoutCancellationException) { throw LiveCallException("Couldn't reach the network to start the call.", e) }
        connection.localDescription.sdp
    }

    override suspend fun accept(answerSdp: String) {
        val ours = synchronized(lock) { native } ?: throw LiveCallException("The call was closed.")
        withContext(Dispatchers.IO) {
            try {
                suspendCancellableCoroutine<Unit> { c ->
                    ours.connection.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, answerSdp), object : SetSessionDescriptionObserver {
                        override fun onSuccess() = c.resume(Unit)
                        override fun onFailure(error: String?) = c.resumeWithException(LiveCallException("The call's answer was refused: $error"))
                    })
                }
            } catch (e: Error) {
                throw LiveCallException("The call's answer was refused: ${e.message}", e)
            }
        }
        applySpeaker(ours)
        scope.launch { measure(ours) }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        synchronized(lock) { native?.let { runCatching { it.track.isEnabled = !muted } } }
    }

    override fun setSpeakerMuted(muted: Boolean) {
        speakerMuted = muted
        synchronized(lock) { native?.let(::applySpeaker) }
    }

    override fun close() {
        val ours = synchronized(lock) {
            if (closed) return
            closed = true
            native.also { native = null }
        }
        state.value = LivePeerState.Closed
        levels.value = LiveCallLevels()
        val measuring = scope.coroutineContext[Job]
        scope.cancel()
        ours?.let { Thread({ runBlocking { measuring?.join() }; teardown(it) }, "kinuvo-call-close").apply { isDaemon = true; start() } }
    }

    private val observer = object : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {}
        override fun onIceGatheringChange(state: RTCIceGatheringState) { if (state == RTCIceGatheringState.COMPLETE) gathered.complete(Unit) }
        override fun onConnectionChange(state: RTCPeerConnectionState) {
            this@JvmLiveCallPeer.state.update { current ->
                if (current == LivePeerState.Failed || current == LivePeerState.Closed) current
                else when (state) {
                    RTCPeerConnectionState.CONNECTED -> LivePeerState.Connected
                    RTCPeerConnectionState.FAILED -> LivePeerState.Failed
                    RTCPeerConnectionState.CLOSED -> LivePeerState.Closed
                    else -> LivePeerState.Connecting
                }
            }
        }
    }

    private suspend fun setLocal(connection: RTCPeerConnection, description: RTCSessionDescription) = suspendCancellableCoroutine { c ->
        connection.setLocalDescription(description, object : SetSessionDescriptionObserver {
            override fun onSuccess() = c.resume(Unit)
            override fun onFailure(error: String?) = c.resumeWithException(LiveCallException("Couldn't prepare the call: $error"))
        })
    }

    private fun applySpeaker(ours: Native) {
        runCatching { ours.connection.setAudioPlayout(!speakerMuted) }
    }

    private suspend fun measure(ours: Native) {
        while (scope.isActive) {
            val (input, output) = withTimeoutOrNull(STATS_TIMEOUT_MILLIS) { loudness(ours.connection) } ?: (0f to 0f)
            levels.value = LiveCallLevels(if (muted) 0f else input, output)
            delay(LEVEL_INTERVAL_MILLIS)
        }
    }

    private suspend fun loudness(connection: RTCPeerConnection) = suspendCancellableCoroutine<Pair<Float, Float>> { c ->
        connection.getStats { report ->
            var input = 0f
            var output = 0f
            for (stat in report.stats.values) {
                val level = (stat.attributes["audioLevel"] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: continue
                when (stat.type) {
                    RTCStatsType.MEDIA_SOURCE -> input = level
                    RTCStatsType.INBOUND_RTP -> output = level
                    else -> {}
                }
            }
            if (c.isActive) c.resume(input to output)
        }
    }

    private fun teardown(ours: Native) {
        runCatching { ours.connection.close() }
        runCatching { ours.sender.dispose() }
        runCatching { ours.track.dispose() }
        ours.audio.close()
    }

    private companion object {
        const val ICE_TIMEOUT_MILLIS = 10_000L
        const val LEVEL_INTERVAL_MILLIS = 80L
        const val STATS_TIMEOUT_MILLIS = 500L
    }
}
