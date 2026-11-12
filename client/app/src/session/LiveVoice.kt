package kinuvo.app.session

import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.catching
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.client.userMessage
import kinuvo.protocol.*
import kinuvo.protocol.voice.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.io.encoding.Base64
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Streaming microphone and speaker for live calls, in mono PCM16. Callbacks may arrive on any thread. */
interface LiveAudio {
    fun onEndRequested(end: () -> Unit) {}
    fun onInputMuteChanged(changed: (Boolean) -> Unit) {}
    fun setInputMuted(muted: Boolean) {}
    val liveAudioSupported: Boolean get() = true
    /** Whether the microphone hears the speaker, so capture is muted while a reply plays. */
    val echoes: Boolean
    fun authorizeAudio(completion: (String?) -> Unit)
    /** Captures the microphone at [sampleRate] in short chunks (100 ms or less) and readies playback at the same rate. */
    fun startLive(sampleRate: Int, onAudio: (ByteArray) -> Unit, onError: (String) -> Unit)
    /** Plays [pcm] after what is already queued. */
    fun playLive(pcm: ByteArray)
    /** Milliseconds queued but not yet heard. */
    fun queuedMillis(): Int
    /** Drops what is queued to play. */
    fun flushLive()
    fun stopLive()
    /** Replaces a short standalone cue without capturing audio or waiting for playback. */
    fun playCue(pcm: ByteArray, sampleRate: Int) {}
    fun stopCue() {}
}

/**
 * Voice chat with the Server's speech-to-speech model: the Device streams its microphone and plays what the model says;
 * the model hears pauses and interruptions itself. Agent work it hands off runs as the Session's messages.
 */
class LiveVoiceChat(
    private val server: ConnectedServer,
    private val audio: LiveAudio,
    private val clock: TimeSource = TimeSource.Monotonic,
    /** The Agent and Session the call joins, starting a new Session if needed. */
    private val openSession: suspend () -> Pair<AgentId, SessionId>?,
) : VoiceChat {
    override val state: StateFlow<VoiceChatState>
        field = MutableStateFlow(VoiceChatState())
    private var generation = 0
    private var call: Job? = null
    private var callId: String? = null
    private var connection: KinuvoClient? = null
    private var ownsAudio = false
    private var reply: String? = null
    /** The reply whose text is done; its audio can still be arriving. */
    private var finished: String? = null
    private val interrupted = mutableSetOf<String>()
    private var buffered = ByteArray(0)
    private var prebuffer: Job? = null
    private var queued = 0
    private val levels = ArrayDeque<Pair<Int, Float>>()
    private var connecting: Job? = null
    private var ready = false
    private var bytesPerMilli = 48
    private var sampleRate = 24_000
    /** A cue queued on the live player, cut when a reply starts. */
    private var cueQueued = false
    /** Until when the microphone stays unsent, so the model doesn't hear a cue. */
    private var quietUntil = clock.markNow()
    private var heldFromMute = false

    override fun start() {
        if (state.value.phase != VoicePhase.Off || !DeviceAudio.acquire(audio, this)) return
        ownsAudio = true
        state.value = VoiceChatState(VoicePhase.Starting, speaker = state.value.speaker, live = true)
        ready = false
        audio.stopCue()
        connecting = server.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                if (state.value.speaker) audio.playCue(VoiceCue.Connecting.pcm(24_000), 24_000)
                delay(1_000)
            }
        }
        val epoch = ++generation
        call = server.scope.launch {
            try { converse() }
            catch (e: CancellationException) { if (generation == epoch) stop(null); throw e }
            catch (e: Exception) { if (generation == epoch) fail(userMessage(e.message) ?: "Live voice failed") }
        }
    }

    private suspend fun converse() {
        audio.onEndRequested(::end)
        val denied = suspendCancellableCoroutine { c -> audio.authorizeAudio { if (c.isActive) c.resumeWith(Result.success(it)) } }
        if (denied != null) error(denied)
        val (agentId, sessionId) = openSession() ?: error("Pick an Agent first")
        val client = server.client ?: error("Not connected")
        connection = client
        coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                client.state.first { it !is ConnectionState.Connected }
                error("The connection to the Server dropped.")
            }
            val outputs = Channel<LiveVoiceOutput>(Channel.UNLIMITED)
            launch(start = CoroutineStart.UNDISPATCHED) { client.events(Api.LiveVoiceOutput).collect { outputs.send(it) } }
            val started = client.call(Api.LiveVoiceStart, LiveVoiceParams(agentId, sessionId))
            callId = started.callId
            bytesPerMilli = started.sampleRate * 2 / 1000
            sampleRate = started.sampleRate
            val microphone = Channel<ByteArray>(Channel.UNLIMITED)
            val epoch = generation
            audio.onInputMuteChanged { muted ->
                server.scope.launch {
                    if (generation == epoch && ownsAudio) {
                        mute(muted, fromSystem = true)
                    }
                }
            }
            audio.startLive(started.sampleRate, { microphone.trySend(it) }, { microphone.close(IllegalStateException(it)) })
            audio.setInputMuted(state.value.paused)
            launch {
                for (pcm in microphone) {
                    state.update { it.copy(inputLevel = if (it.paused) 0f else pcmLevel(pcm)) }
                    if (!ready || state.value.paused || quietUntil.hasNotPassedNow() || (audio.echoes && audio.queuedMillis() > 0)) continue
                    client.emit(Api.LiveVoiceInput, LiveVoiceInput.Audio(started.callId, Base64.encode(pcm)))
                }
            }
            // Played on the live player, whose echo cancellation keeps it from the model.
            if (!audio.echoes) launch {
                state.map { it.phase == VoicePhase.Thinking }.distinctUntilChanged().collectLatest { thinking ->
                    if (!thinking) return@collectLatest
                    delay(700)
                    while (true) {
                        if (state.value.speaker && audio.queuedMillis() == 0) { audio.playLive(VoiceCue.Thinking.pcm(started.sampleRate)); cueQueued = true }
                        delay(1_600)
                    }
                }
            }
            launch {
                while (isActive) {
                    delay(40)
                    val played = queued - buffered.size - audio.queuedMillis() * bytesPerMilli
                    while (levels.isNotEmpty() && levels.first().first <= played) levels.removeFirst()
                    state.update { it.copy(outputLevel = if (played < 0 || audio.queuedMillis() == 0) 0f else levels.firstOrNull()?.second ?: 0f) }
                    val item = reply ?: continue
                    val waiting = audio.queuedMillis()
                    client.emit(Api.LiveVoiceInput, LiveVoiceInput.Played(started.callId, item, ((queued - buffered.size) / bytesPerMilli - waiting).coerceAtLeast(0)))
                    if (waiting == 0 && buffered.isEmpty() && item == finished) {
                        reply = null
                        state.update { if (it.phase == VoicePhase.Speaking) it.copy(phase = VoicePhase.Listening) else it }
                    }
                }
            }
            for (output in outputs) {
                if (output.callId != started.callId) continue
                when (output) {
                    is LiveVoiceOutput.Ready -> {
                        stopConnecting()
                        if (!ready) {
                            ready = true
                            if (state.value.speaker) audio.playLive(VoiceCue.Ready.pcm(started.sampleRate))
                        }
                        state.update { if (it.phase == VoicePhase.Starting) it.copy(phase = VoicePhase.Listening) else it }
                    }
                    is LiveVoiceOutput.Listening -> state.update { if (it.phase == VoicePhase.Speaking && (audio.queuedMillis() > 0 || buffered.isNotEmpty())) it else it.copy(phase = VoicePhase.Listening) }
                    is LiveVoiceOutput.Thinking -> state.update { it.copy(phase = VoicePhase.Thinking) }
                    is LiveVoiceOutput.Audio -> if (state.value.speaker && output.item !in interrupted) {
                        if (cueQueued) { cueQueued = false; if (reply == null) audio.flushLive() }
                        if (reply != output.item) { drainBuffer(); reply = output.item; queued = 0; levels.clear(); if (finished != output.item) finished = null }
                        val pcm = Base64.decode(output.base64)
                        queued += pcm.size
                        levels.addLast(queued to pcmLevel(pcm))
                        if (buffered.isNotEmpty() || audio.queuedMillis() == 0) {
                            buffered += pcm
                            if (buffered.size >= bytesPerMilli * 80 || finished == output.item) drainBuffer()
                            else if (prebuffer == null) prebuffer = launch { delay(100); drainBuffer() }
                        } else audio.playLive(pcm)
                        state.update { it.copy(phase = VoicePhase.Speaking) }
                    }
                    is LiveVoiceOutput.Transcript -> state.update { if (output.user) it.copy(heard = output.text) else it.copy(caption = output.text) }
                    is LiveVoiceOutput.Done -> { finished = output.item; if (reply == output.item) drainBuffer() }
                    is LiveVoiceOutput.Interrupted -> { discardReply(); audio.flushLive(); state.update { it.copy(phase = VoicePhase.Listening) } }
                    is LiveVoiceOutput.Ended -> { output.error?.let(::error); break }
                }
            }
            coroutineContext.cancelChildren()
        }
        stop(null)
    }

    override fun end() = stop(null, userEnded = true)

    /** A failed call stays live with its [error] until it is ended. */
    private fun stop(error: String?, userEnded: Boolean = false) {
        if (call == null && callId == null && !state.value.live) return
        val endedCue = userEnded && ownsAudio && state.value.speaker
        stopConnecting()
        generation++
        val id = callId
        callId = null; discardReply(); interrupted.clear(); cueQueued = false; heldFromMute = false
        call?.cancel(); call = null
        if (ownsAudio) {
            audio.onInputMuteChanged {}
            audio.stopLive()
            if (endedCue) audio.playCue(VoiceCue.Ended.pcm(24_000), 24_000)
            audio.onEndRequested {}
            DeviceAudio.release(audio, this)
            ownsAudio = false
        }
        val client = connection
        connection = null
        if (id != null && client?.state?.value is ConnectionState.Connected) server.scope.launch { catching { client.call(Api.LiveVoiceEnd, LiveVoiceRef(id)) } }
        state.value = VoiceChatState(speaker = state.value.speaker, error = error, live = error != null)
    }

    /** Mutes the microphone only; the Agent's replies keep playing. */
    override fun pause() = mute(true, fromSystem = false)

    override fun resume() = mute(false, fromSystem = false)

    /** The system plays its own cue when it mutes, e.g. from AirPods. */
    private fun mute(muted: Boolean, fromSystem: Boolean) {
        if (state.value.phase == VoicePhase.Off || state.value.paused == muted) return
        heldFromMute = false
        state.update { it.copy(paused = muted, holding = false, inputLevel = 0f) }
        if (!fromSystem) { audio.setInputMuted(muted); cue(if (muted) VoiceCue.Muted else VoiceCue.Unmuted) }
        send { if (muted) LiveVoiceInput.Pause(it) else LiveVoiceInput.Resume(it) }
    }

    /** The model hears turns itself, so holding cuts the reply short and, while muted, unmutes until [letGo]. */
    override fun hold() {
        if (state.value.phase in setOf(VoicePhase.Off, VoicePhase.Starting) || state.value.holding) return
        cutIn()
        val muted = state.value.paused
        state.update { it.copy(phase = VoicePhase.Listening, holding = true, paused = false) }
        heldFromMute = muted
        if (muted) { audio.setInputMuted(false); send { LiveVoiceInput.Resume(it) } }
    }

    override fun letGo() {
        if (!state.value.holding) return
        val muted = heldFromMute
        heldFromMute = false
        state.update { it.copy(holding = false, paused = muted) }
        if (muted) { audio.setInputMuted(true); send { LiveVoiceInput.Pause(it) } }
    }

    override fun interrupt() {
        if (reply == null) return
        cutIn()
        cue(VoiceCue.Interrupt)
        state.update { it.copy(phase = VoicePhase.Listening) }
    }

    override fun attention() = cue(VoiceCue.Attention)

    override fun background() {}
    override fun foreground() {}

    override fun speaker(on: Boolean) {
        state.update { it.copy(speaker = on) }
        if (!on && ownsAudio) { audio.stopCue(); cutIn(); audio.flushLive() }
    }

    private fun cue(cue: VoiceCue) {
        if (!ownsAudio || !ready || !state.value.speaker) return
        quietUntil = clock.markNow() + (cue.millis() + 80).milliseconds
        audio.playCue(cue.pcm(sampleRate), sampleRate)
    }

    private fun stopConnecting() {
        connecting?.cancel(); connecting = null
        if (ownsAudio) audio.stopCue()
    }

    private fun cutIn() {
        val item = reply ?: return
        val heard = ((queued - buffered.size) / bytesPerMilli - audio.queuedMillis()).coerceAtLeast(0)
        discardReply()
        audio.flushLive()
        send { LiveVoiceInput.Interrupt(it, item, heard) }
    }

    private fun drainBuffer() {
        prebuffer?.cancel()
        prebuffer = null
        if (buffered.isNotEmpty()) audio.playLive(buffered)
        buffered = ByteArray(0)
    }

    private fun discardReply() {
        reply?.let { interrupted += it }
        reply = null
        levels.clear()
        finished = null
        prebuffer?.cancel()
        prebuffer = null
        buffered = ByteArray(0)
    }

    private fun send(input: (String) -> LiveVoiceInput) {
        val id = callId ?: return
        val client = connection ?: return
        server.scope.launch { catching { client.emit(Api.LiveVoiceInput, input(id)) } }
    }

    private fun fail(error: String) = stop(error)
}

/** Live calls when the Server offers them and the Device can stream audio, else [speech]; decided at each start. */
class PickedVoiceChat(
    private val speech: VoiceChat,
    private val live: VoiceChat?,
    private val scope: CoroutineScope,
    private val offered: suspend () -> Boolean,
) : VoiceChat {
    private var active = speech
    private var picking: Job? = null
    override val state: StateFlow<VoiceChatState>
        field = MutableStateFlow(VoiceChatState())

    init {
        scope.launch {
            merge(speech.state.map { speech to it }, live?.state?.map { live to it } ?: emptyFlow()).collect { (chat, s) ->
                if (chat === live && s.live && active.state.value.phase == VoicePhase.Off) active = chat
                if (chat === active && picking?.isActive != true) state.value = s
            }
        }
    }

    override fun start() {
        if (state.value.phase != VoicePhase.Off) return
        if (live == null) return speech.also { active = it }.start()
        state.value = VoiceChatState(VoicePhase.Starting, speaker = state.value.speaker)
        picking = scope.launch {
            active = if (catching { offered() }.getOrDefault(false)) live else speech
            active.speaker(state.value.speaker)
            active.start()
            state.value = active.state.value
        }
    }

    override fun end() { picking?.cancel(); active.end(); state.value = active.state.value }
    override fun pause() = active.pause()
    override fun resume() = active.resume()
    override fun hold() = active.hold()
    override fun letGo() = active.letGo()
    override fun interrupt() = active.interrupt()
    override fun attention() = active.attention()
    override fun speaker(on: Boolean) { speech.speaker(on); live?.speaker(on) }
    override fun background() = active.background()
    override fun foreground() = active.foreground()
}

internal fun pcmLevel(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    var sum = 0.0
    for (i in 0 until pcm.size - 1 step 2) {
        val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
        sum += sample * sample
    }
    return sqrt(sum / (pcm.size / 2)).toFloat().coerceIn(0f, 1f)
}
