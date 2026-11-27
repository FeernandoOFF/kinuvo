package kinuvo.ui.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaRecorder
import kinuvo.app.session.LiveCallException
import kinuvo.app.session.LiveCallLevels
import kinuvo.app.session.LiveCallPeer
import kinuvo.app.session.advancedTo
import kinuvo.protocol.voice.LivePeerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Android [LiveCallPeer]: libwebrtc with its Java audio device module on the voice-communication source and
 * stream, using the phone's echo canceller and noise suppressor where it has them and WebRTC's own otherwise. [release]
 * runs once the audio is torn down, to hand back the audio route and background service the call held.
 */
internal class AndroidLiveCallPeer(private val context: Context, private val release: () -> Unit) : LiveCallPeer {
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

    private class Native(
        val factory: PeerConnectionFactory,
        val module: JavaAudioDeviceModule,
        val source: AudioSource,
        val track: AudioTrack,
        val sender: RtpSender,
        val connection: PeerConnection,
    )

    override suspend fun createOffer(): String = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw LiveCallException("Microphone access is denied. Enable it in Android Settings.")
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) throw LiveCallException("This phone has no microphone.")
        val ours = build()
        synchronized(lock) {
            if (closed) { teardown(ours); throw LiveCallException("The call was closed.") }
            native = ours
        }
        ours.track.setEnabled(!muted)
        ours.module.setSpeakerMute(speakerMuted)
        val offer = suspendCancellableCoroutine<SessionDescription> { c ->
            ours.connection.createOffer(object : Sdp() {
                override fun onCreateSuccess(description: SessionDescription) = c.resume(description)
                override fun onCreateFailure(error: String?) = c.resumeWithException(LiveCallException("Couldn't prepare the call: $error"))
            }, MediaConstraints())
        }
        suspendCancellableCoroutine<Unit> { c ->
            ours.connection.setLocalDescription(object : Sdp() {
                override fun onSetSuccess() = c.resume(Unit)
                override fun onSetFailure(error: String?) = c.resumeWithException(LiveCallException("Couldn't prepare the call: $error"))
            }, offer)
        }
        try { withTimeout(ICE_TIMEOUT_MILLIS) { gathered.await() } }
        catch (e: TimeoutCancellationException) { throw LiveCallException("Couldn't reach the network to start the call.", e) }
        ours.connection.localDescription.description
    }

    override suspend fun accept(answerSdp: String) {
        val ours = synchronized(lock) { native } ?: throw LiveCallException("The call was closed.")
        suspendCancellableCoroutine<Unit> { c ->
            ours.connection.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() = c.resume(Unit)
                override fun onSetFailure(error: String?) = c.resumeWithException(LiveCallException("The call's answer was refused: $error"))
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }
        scope.launch { measure(ours) }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        synchronized(lock) { native?.let { runCatching { it.track.setEnabled(!muted) } } }
    }

    override fun setSpeakerMuted(muted: Boolean) {
        speakerMuted = muted
        synchronized(lock) { native?.let { runCatching { it.module.setSpeakerMute(muted) } } }
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
        if (ours == null) { release(); return }
        Thread({ runBlocking { measuring?.join() }; teardown(ours) }, "kinuvo-call-close").start()
    }

    private fun build(): Native {
        try {
            WebRtcRuntime.initialize(context)
            val module = JavaAudioDeviceModule.builder(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(message: String?) = fail()
                    override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, message: String?) = fail()
                    override fun onWebRtcAudioRecordError(message: String?) = fail()
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(message: String?) = fail()
                    override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode?, message: String?) = fail()
                    override fun onWebRtcAudioTrackError(message: String?) = fail()
                })
                .createAudioDeviceModule()
            var factory: PeerConnectionFactory? = null
            var source: AudioSource? = null
            var track: AudioTrack? = null
            var connection: PeerConnection? = null
            try {
                factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
                source = factory.createAudioSource(MediaConstraints().apply {
                    optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                })
                track = factory.createAudioTrack("kinuvo-microphone", source)
                val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                }
                connection = factory.createPeerConnection(config, observer) ?: throw LiveCallException("Couldn't start the call.")
                val sender = connection.addTrack(track, listOf("kinuvo"))
                return Native(factory, module, source, track, sender, connection)
            } catch (e: Throwable) {
                runCatching { connection?.close() }
                runCatching { connection?.dispose() }
                runCatching { track?.dispose() }
                runCatching { source?.dispose() }
                runCatching { factory?.dispose() }
                runCatching { module.release() }
                throw e
            }
        } catch (e: LiveCallException) {
            throw e
        } catch (e: LinkageError) {
            throw LiveCallException("Live voice isn't available on this phone.", e)
        } catch (e: RuntimeException) {
            throw LiveCallException("Couldn't start the call's audio: ${e.message}", e)
        }
    }

    private fun fail() = state.update { it.advancedTo(LivePeerState.Failed) }

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val next = when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> LivePeerState.Connected
                PeerConnection.PeerConnectionState.FAILED -> LivePeerState.Failed
                PeerConnection.PeerConnectionState.CLOSED -> LivePeerState.Closed
                else -> LivePeerState.Connecting
            }
            state.update { it.advancedTo(next) }
        }
        override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private suspend fun measure(ours: Native) {
        while (scope.isActive) {
            val (input, output) = withTimeoutOrNull(STATS_TIMEOUT_MILLIS) { loudness(ours.connection) } ?: (0f to 0f)
            levels.value = LiveCallLevels(if (muted) 0f else input, output)
            delay(LEVEL_INTERVAL_MILLIS)
        }
    }

    private suspend fun loudness(connection: PeerConnection) = suspendCancellableCoroutine<Pair<Float, Float>> { c ->
        connection.getStats { report ->
            var input = 0f
            var output = 0f
            for (stat in report.statsMap.values) {
                val level = (stat.members["audioLevel"] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: continue
                when (stat.type) {
                    "media-source" -> input = level
                    "inbound-rtp" -> output = level
                }
            }
            if (c.isActive) c.resume(input to output)
        }
    }

    private fun teardown(ours: Native) {
        runCatching { ours.connection.close() }
        runCatching { ours.connection.dispose() }
        runCatching { ours.track.dispose() }
        runCatching { ours.source.dispose() }
        runCatching { ours.factory.dispose() }
        runCatching { ours.module.release() }
        release()
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    private companion object {
        const val ICE_TIMEOUT_MILLIS = 10_000L
        const val LEVEL_INTERVAL_MILLIS = 100L
        const val STATS_TIMEOUT_MILLIS = 500L
    }
}

private object WebRtcRuntime {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        initialized = true
    }
}
