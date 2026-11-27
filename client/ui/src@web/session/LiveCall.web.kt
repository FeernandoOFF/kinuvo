@file:OptIn(ExperimentalWasmJsInterop::class)

package kinuvo.ui.session

import kinuvo.app.session.LiveCallException
import kinuvo.app.session.LiveCallLevels
import kinuvo.app.session.LiveCallPeer
import kinuvo.app.session.advancedTo
import kinuvo.protocol.voice.LivePeerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.js.Promise

internal fun browserCanCall(): Boolean = js("typeof RTCPeerConnection === 'function' && !!navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function'")

/** Resolves to the microphone's MediaStream, or to the DOMException's name when the browser refuses it. */
private fun requestMicrophone(): Promise<JsAny> = js(
    """navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } })
        .then(function (stream) { return stream; }, function (e) { return String((e && e.name) || 'Error'); })"""
)

private fun isFailure(result: JsAny): Boolean = js("typeof result === 'string'")

private fun text(value: JsAny): String = js("String(value)")

private fun newConnection(): JsAny = js("new RTCPeerConnection({ bundlePolicy: 'max-bundle' })")

/** Sends the microphone on the connection's one audio transceiver, returning its track. */
private fun addMicrophone(connection: JsAny, stream: JsAny): JsAny = js("(function () { var track = stream.getAudioTracks()[0]; connection.addTrack(track, stream); return track; })()")

private fun watch(connection: JsAny, changed: () -> Unit, gathered: () -> Unit, remote: (JsAny) -> Unit): Unit = js(
    """(function () {
        connection.addEventListener('connectionstatechange', function () { changed(); });
        connection.addEventListener('iceconnectionstatechange', function () { changed(); });
        connection.addEventListener('icegatheringstatechange', function () { if (connection.iceGatheringState === 'complete') gathered(); });
        connection.addEventListener('track', function (e) { remote((e.streams && e.streams[0]) || new MediaStream([e.track])); });
    })()"""
)

private fun connectionState(connection: JsAny): String = js("connection.connectionState || connection.iceConnectionState")

private fun gatheringComplete(connection: JsAny): Boolean = js("connection.iceGatheringState === 'complete'")

/** Resolves to null once the offer is the local description, else to why not. */
private fun prepareOffer(connection: JsAny): Promise<JsAny?> = js(
    """connection.createOffer().then(function (offer) { return connection.setLocalDescription(offer); })
        .then(function () { return null; }, function (e) { return String((e && e.message) || e); })"""
)

private fun localSdp(connection: JsAny): String = js("connection.localDescription.sdp")

/** Resolves to null once the answer is the remote description, else to why not. */
private fun applyAnswer(connection: JsAny, sdp: String): Promise<JsAny?> = js(
    """connection.setRemoteDescription({ type: 'answer', sdp: sdp })
        .then(function () { return null; }, function (e) { return String((e && e.message) || e); })"""
)

private fun newSpeaker(): JsAny = js(
    """(function () {
        var audio = document.createElement('audio');
        audio.autoplay = true;
        audio.setAttribute('playsinline', '');
        audio.style.display = 'none';
        document.body.appendChild(audio);
        return audio;
    })()"""
)

/** Plays [stream]; a browser that refuses without a gesture is retried on the next tap or key press. */
private fun play(audio: JsAny, stream: JsAny): Unit = js(
    """(function () {
        audio.srcObject = stream;
        var retry = function () {
            document.removeEventListener('pointerdown', retry, true);
            document.removeEventListener('keydown', retry, true);
            audio.play().catch(function () {});
        };
        audio.play().catch(function () {
            document.addEventListener('pointerdown', retry, true);
            document.addEventListener('keydown', retry, true);
        });
    })()"""
)

private fun enable(track: JsAny, enabled: Boolean): Unit = js("track.enabled = enabled")

private fun muteSpeaker(audio: JsAny, muted: Boolean): Unit = js("audio.muted = muted")

/** "input,output" loudness, 0..1 each; zero where the browser doesn't report it. */
private fun readLevels(connection: JsAny): Promise<JsString> = js(
    """connection.getStats().then(function (report) {
        var input = 0, output = 0;
        report.forEach(function (stat) {
            if (typeof stat.audioLevel !== 'number') return;
            if (stat.type === 'media-source') input = stat.audioLevel;
            else if (stat.type === 'inbound-rtp') output = stat.audioLevel;
        });
        return input + ',' + output;
    }, function () { return '0,0'; })"""
)

private fun stopMicrophone(stream: JsAny): Unit = js("stream.getTracks().forEach(function (track) { track.stop(); })")

private fun closeConnection(connection: JsAny): Unit = js("connection.close()")

private fun removeSpeaker(audio: JsAny): Unit = js("(function () { audio.pause(); audio.srcObject = null; audio.remove(); })()")

/**
 * A call carried by the browser's `RTCPeerConnection`, with the microphone from `getUserMedia` (echo cancellation,
 * noise suppression and auto gain on) and the model's voice in an `<audio>` element. Levels come from `getStats`.
 */
internal class WebLiveCallPeer : LiveCallPeer {
    override val state: StateFlow<LivePeerState>
        field = MutableStateFlow(LivePeerState.Connecting)
    override val levels: StateFlow<LiveCallLevels>
        field = MutableStateFlow(LiveCallLevels())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gathered = CompletableDeferred<Unit>()
    private var connection: JsAny? = null
    private var stream: JsAny? = null
    private var track: JsAny? = null
    private var speaker: JsAny? = null
    private var closed = false
    private var muted = false
    private var speakerMuted = false

    override suspend fun createOffer(): String {
        if (closed) throw LiveCallException("The call was closed.")
        if (!browserCanCall()) throw LiveCallException("This browser can't hold a live call. Use a current browser over HTTPS.")
        val microphone = requestMicrophone().await()
        if (isFailure(microphone)) throw LiveCallException(microphoneFailure(text(microphone)))
        if (closed) { stopMicrophone(microphone); throw LiveCallException("The call was closed.") }
        stream = microphone
        val peer = try {
            newConnection().also { connection = it; track = addMicrophone(it, microphone) }
        } catch (e: Throwable) {
            release()
            throw LiveCallException("This browser can't hold a live call.", e)
        }
        track?.let { enable(it, !muted) }
        watch(peer, ::connectionChanged, { gathered.complete(Unit) }, ::playRemote)
        prepareOffer(peer).await()?.let { throw LiveCallException("Couldn't prepare the call: ${text(it)}") }
        if (!gatheringComplete(peer)) {
            try { withTimeout(ICE_TIMEOUT_MILLIS) { gathered.await() } }
            catch (e: TimeoutCancellationException) { throw LiveCallException("Couldn't reach the network to start the call.", e) }
        }
        return localSdp(peer)
    }

    override suspend fun accept(answerSdp: String) {
        val peer = connection ?: throw LiveCallException("The call was closed.")
        applyAnswer(peer, answerSdp).await()?.let { throw LiveCallException("The call's answer was refused: ${text(it)}") }
        connectionChanged()
        if (!closed) scope.launch { measure(peer) }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        track?.let { enable(it, !muted) }
    }

    override fun setSpeakerMuted(muted: Boolean) {
        speakerMuted = muted
        speaker?.let { muteSpeaker(it, muted) }
    }

    override fun close() {
        if (closed) return
        closed = true
        state.value = LivePeerState.Closed
        levels.value = LiveCallLevels()
        scope.cancel()
        release()
    }

    private fun release() {
        val peer = connection
        connection = null
        speaker?.let { runCatching { removeSpeaker(it) } }
        speaker = null
        track = null
        peer?.let { runCatching { closeConnection(it) } }
        stream?.let { runCatching { stopMicrophone(it) } }
        stream = null
    }

    private fun connectionChanged() {
        val peer = connection ?: return
        val next = when (connectionState(peer)) {
            "connected", "completed" -> LivePeerState.Connected
            "failed" -> LivePeerState.Failed
            "closed" -> LivePeerState.Closed
            else -> LivePeerState.Connecting
        }
        state.update { it.advancedTo(next) }
    }

    private fun playRemote(remote: JsAny) {
        if (closed) return
        val audio = speaker ?: newSpeaker().also { speaker = it }
        muteSpeaker(audio, speakerMuted)
        play(audio, remote)
    }

    private suspend fun measure(peer: JsAny) {
        while (scope.isActive && connection === peer) {
            val packed = withTimeoutOrNull(STATS_TIMEOUT_MILLIS) { text(readLevels(peer).await()) }
            val measured = packed?.let(::parseLevels) ?: LiveCallLevels()
            if (!closed) levels.value = if (muted) measured.copy(input = 0f) else measured
            delay(LEVEL_INTERVAL_MILLIS)
        }
    }

    private companion object {
        const val ICE_TIMEOUT_MILLIS = 10_000L
        const val LEVEL_INTERVAL_MILLIS = 80L
        const val STATS_TIMEOUT_MILLIS = 500L
    }
}
