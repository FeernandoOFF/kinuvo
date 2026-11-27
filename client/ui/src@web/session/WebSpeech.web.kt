@file:OptIn(ExperimentalWasmJsInterop::class)

package kinuvo.ui.session

import kinuvo.app.session.LiveAudio
import kinuvo.app.session.LiveCallPeer
import kinuvo.app.session.SpeechEngine
import kotlin.io.encoding.Base64

private const val CALLS_ONLY = "The browser only offers live calls, which need a Server signed in to ChatGPT."

private fun newAudioContext(): JsAny = js("new (window.AudioContext || window.webkitAudioContext)()")

private fun resumeContext(context: JsAny): Unit = js("context.resume().catch(function () {})")

/** Plays 16-bit little-endian mono PCM, given as base64, returning the source to stop it with. */
private fun playPcm(context: JsAny, base64: String, rate: Int): JsAny = js(
    """(function () {
        var raw = atob(base64);
        var count = raw.length >> 1;
        var buffer = context.createBuffer(1, count, rate);
        var data = buffer.getChannelData(0);
        for (var i = 0; i < count; i++) data[i] = ((raw.charCodeAt(2 * i) | (raw.charCodeAt(2 * i + 1) << 8)) << 16 >> 16) / 32768;
        var source = context.createBufferSource();
        source.buffer = buffer;
        source.connect(context.destination);
        source.start();
        return source;
    })()"""
)

private fun stopSource(source: JsAny): Unit = js("source.stop()")

/**
 * The browser's voice: it holds Device-held live calls with [WebLiveCallPeer] and plays their cues with one audio context kept for the page's life. Dictation, reading
 * aloud stay unsupported.
 */
internal class WebSpeech : SpeechEngine, LiveAudio {
    override val speaks = false
    override val callPeerSupported = true
    private var context: JsAny? = null
    private var cue: JsAny? = null

    override fun createCallPeer(): LiveCallPeer = WebLiveCallPeer()

    /** Opens the audio output while the tap that started the call still counts as a gesture; the microphone prompt comes with the offer. */
    override fun authorizeAudio(completion: (String?) -> Unit) {
        runCatching { (context ?: newAudioContext().also { context = it }).let(::resumeContext) }
        completion(null)
    }

    override fun playCue(pcm: ByteArray, sampleRate: Int) {
        stopCue()
        val output = context ?: return
        cue = runCatching { playPcm(output, Base64.encode(pcm), sampleRate) }.getOrNull()
    }

    override fun stopCue() {
        cue?.let { runCatching { stopSource(it) } }
        cue = null
    }

    override fun authorize(completion: (String?) -> Unit) = completion(CALLS_ONLY)
    override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) = onError(CALLS_ONLY)
    override fun speak(text: String, onDone: () -> Unit) = onDone()
    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) = onError(CALLS_ONLY)
    override fun stopSpeaking() {}
    override fun stop() {}
}
