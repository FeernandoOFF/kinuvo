package kinuvo.app.session

import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.catching
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.client.userMessage
import kinuvo.protocol.Api
import kinuvo.protocol.Method
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.voice.LiveCallParams
import kinuvo.protocol.voice.LiveCallPhase
import kinuvo.protocol.voice.LiveCallUpdate
import kinuvo.protocol.voice.LivePeerParams
import kinuvo.protocol.voice.LivePeerState
import kinuvo.protocol.voice.LiveCallRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** What a Device provides a live call besides its [LiveCallPeer]: microphone permission, cues and system call controls. Callbacks may arrive on any thread. */
interface LiveAudio {
    fun onEndRequested(end: () -> Unit) {}
    fun onInputMuteChanged(changed: (Boolean) -> Unit) {}
    fun setInputMuted(muted: Boolean) {}
    fun authorizeAudio(completion: (String?) -> Unit)
    /** Replaces a short standalone cue without waiting for playback. */
    fun playCue(pcm: ByteArray, sampleRate: Int) {}
    fun stopCue() {}
    /** Whether this Device can hold a call itself, with [createCallPeer]. */
    val callPeerSupported: Boolean get() = false
    /** A WebRTC peer for a call the Device holds. */
    fun createCallPeer(): LiveCallPeer? = null
}

/**
 * Voice chat where the Device holds the call: [LiveCallPeer] carries the microphone and speaker to the Server's model
 * over WebRTC and the Server brokers it, delegating work to the Agent and reporting what it hears and says.
 */
class LiveCallVoiceChat(
    private val server: ConnectedServer,
    private val audio: LiveAudio,
    /** The Agent and Session the call joins, starting a new Session if needed. */
    private val openSession: suspend () -> Pair<AgentId, SessionId>?,
) : VoiceChat {
    override val state: StateFlow<VoiceChatState>
        field = MutableStateFlow(VoiceChatState())
    private var generation = 0
    private var call: Job? = null
    private var callId: String? = null
    private var connection: KinuvoClient? = null
    private var peer: LiveCallPeer? = null
    private var ownsAudio = false
    private var ready = false
    private var connecting: Job? = null
    private var heldFromMute = false

    override fun start() {
        if (state.value.phase != VoicePhase.Off || !DeviceAudio.acquire(audio, this)) return
        ownsAudio = true
        state.value = VoiceChatState(VoicePhase.Starting, speaker = state.value.speaker, live = true)
        ready = false
        audio.stopCue()
        connecting = server.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                if (state.value.speaker) audio.playCue(VoiceCue.Connecting.pcm(CUE_RATE), CUE_RATE)
                delay(1_000)
            }
        }
        val epoch = ++generation
        call = server.scope.launch {
            try { converse() }
            catch (e: CancellationException) { if (generation == epoch) stop(null); throw e }
            catch (e: Exception) { if (generation == epoch) stop(userMessage(e.message) ?: "Live voice failed") }
        }
    }

    private suspend fun converse() {
        audio.onEndRequested(::end)
        val denied = suspendCancellableCoroutine { c -> audio.authorizeAudio { if (c.isActive) c.resumeWith(Result.success(it)) } }
        if (denied != null) error(denied)
        val local = audio.createCallPeer() ?: error("Live voice isn't available on this device.")
        peer = local
        local.setSpeakerMuted(!state.value.speaker)
        local.setMuted(state.value.paused)
        val (agentId, sessionId) = openSession() ?: error("Pick an Agent first")
        val client = server.client ?: error("Not connected")
        connection = client
        coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                client.state.first { it !is ConnectionState.Connected }
                error("The connection to the Server dropped.")
            }
            val updates = Channel<LiveCallUpdate>(Channel.UNLIMITED)
            launch(start = CoroutineStart.UNDISPATCHED) { client.events(Api.LiveCallUpdate).collect { updates.send(it) } }
            val answer = client.call(Api.LiveCallStart, LiveCallParams(agentId, sessionId, local.createOffer()))
            callId = answer.callId
            local.accept(answer.answerSdp)
            val epoch = generation
            audio.onInputMuteChanged { muted ->
                server.scope.launch { if (generation == epoch && ownsAudio) mute(muted, fromSystem = true) }
            }
            audio.setInputMuted(state.value.paused)
            launch {
                local.state.collect { peerState ->
                    catching { client.call(Api.LiveCallPeer, LivePeerParams(answer.callId, peerState)) }
                    if (peerState == LivePeerState.Failed) error("The call's connection failed. Check your network and try again.")
                }
            }
            launch {
                local.levels.collect { levels -> state.update { it.copy(inputLevel = if (it.paused) 0f else levels.input, outputLevel = levels.output) } }
            }
            for (update in updates) {
                if (update.callId != answer.callId) continue
                when (update) {
                    is LiveCallUpdate.Phase -> {
                        attached()
                        val phase = when (update.phase) {
                            LiveCallPhase.Listening -> VoicePhase.Listening
                            LiveCallPhase.Speaking -> VoicePhase.Speaking
                            LiveCallPhase.Thinking -> VoicePhase.Thinking
                            LiveCallPhase.Paused -> null
                        }
                        if (phase != null) state.update { it.copy(phase = phase) }
                    }
                    is LiveCallUpdate.Transcript -> state.update { if (update.user) it.copy(heard = update.text) else it.copy(caption = update.text) }
                    is LiveCallUpdate.Ended -> {
                        callId = null
                        update.error?.let(::error)
                        break
                    }
                }
            }
            coroutineContext.cancelChildren()
        }
        stop(null)
    }

    override fun end() = stop(null, userEnded = true)

    /** A failed call stays live with its [error] until it is ended. */
    private fun stop(error: String?, userEnded: Boolean = false) {
        if (call == null && callId == null && peer == null && !state.value.live) return
        val endedCue = userEnded && ownsAudio && state.value.speaker
        stopConnecting()
        generation++
        val id = callId
        callId = null; ready = false; heldFromMute = false
        call?.cancel(); call = null
        val local = peer
        peer = null
        local?.setMuted(true)
        val client = connection
        connection = null
        if (local != null) {
            if (id != null && client?.state?.value is ConnectionState.Connected) {
                server.scope.launch {
                    try { withTimeoutOrNull(END_TIMEOUT_MILLIS) { catching { client.call(Api.LiveCallEnd, LiveCallRef(id)) } } }
                    finally { local.close() }
                }
            } else local.close()
        }
        if (ownsAudio) {
            audio.onInputMuteChanged {}
            if (endedCue) audio.playCue(VoiceCue.Ended.pcm(CUE_RATE), CUE_RATE)
            audio.onEndRequested {}
            DeviceAudio.release(audio, this)
            ownsAudio = false
        }
        state.value = VoiceChatState(speaker = state.value.speaker, error = error, live = error != null)
    }

    override fun pause() = mute(true, fromSystem = false)

    override fun resume() = mute(false, fromSystem = false)

    /** The system plays its own cue when it mutes, e.g. from AirPods. */
    private fun mute(muted: Boolean, fromSystem: Boolean) {
        if (state.value.phase == VoicePhase.Off || state.value.paused == muted) return
        heldFromMute = false
        state.update { it.copy(paused = muted, holding = false, inputLevel = 0f) }
        peer?.setMuted(muted)
        if (!fromSystem) { audio.setInputMuted(muted); cue(if (muted) VoiceCue.Muted else VoiceCue.Unmuted) }
        control(if (muted) Api.LiveCallPause else Api.LiveCallResume)
    }

    /** The model hears turns itself, so holding only unmutes a paused call until [letGo]. */
    override fun hold() {
        if (state.value.phase in setOf(VoicePhase.Off, VoicePhase.Starting) || state.value.holding) return
        val muted = state.value.paused
        state.update { it.copy(holding = true, paused = false) }
        heldFromMute = muted
        if (muted) { peer?.setMuted(false); audio.setInputMuted(false); control(Api.LiveCallResume) }
    }

    override fun letGo() {
        if (!state.value.holding) return
        val muted = heldFromMute
        heldFromMute = false
        state.update { it.copy(holding = false, paused = muted) }
        if (muted) { peer?.setMuted(true); audio.setInputMuted(true); control(Api.LiveCallPause) }
    }

    override fun attention() = cue(VoiceCue.Attention)

    override fun background() {}
    override fun foreground() {}

    override fun speaker(on: Boolean) {
        state.update { it.copy(speaker = on) }
        peer?.setSpeakerMuted(!on)
        if (!on && ownsAudio) audio.stopCue()
    }

    /** The Server has attached the call to the model: the first thing it says about it. */
    private fun attached() {
        stopConnecting()
        if (!ready) {
            ready = true
            cue(VoiceCue.Ready)
            if (state.value.paused) control(Api.LiveCallPause)
        }
        state.update { if (it.phase == VoicePhase.Starting) it.copy(phase = VoicePhase.Listening) else it }
    }

    private fun cue(cue: VoiceCue) {
        if (!ownsAudio || !ready || !state.value.speaker) return
        audio.playCue(cue.pcm(CUE_RATE), CUE_RATE)
    }

    private fun stopConnecting() {
        connecting?.cancel(); connecting = null
        if (ownsAudio) audio.stopCue()
    }

    private fun control(method: Method<LiveCallRef, Unit>) {
        val id = callId ?: return
        val client = connection ?: return
        server.scope.launch { catching { client.call(method, LiveCallRef(id)) } }
    }

    private companion object {
        const val CUE_RATE = 24_000
        const val END_TIMEOUT_MILLIS = 3_000L
    }
}
