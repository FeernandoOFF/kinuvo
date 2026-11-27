package kinuvo.app.native

import kinuvo.app.session.LiveCallException
import kinuvo.app.session.LiveCallLevels
import kinuvo.app.session.LiveCallPeer
import kinuvo.app.session.advancedTo
import kinuvo.protocol.voice.LivePeerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A call peer a native app implements with callbacks, e.g. Swift over WebRTC; [liveCallPeer] adapts it to a [LiveCallPeer].
 * Callbacks may arrive on any thread, each completion exactly once.
 */
interface NativeLiveCallPeer {
    /** Called once, before anything else; the peer reports its state and loudness through [events]. */
    fun attach(events: NativeLiveCallEvents)
    fun createOffer(onOffer: (String) -> Unit, onFailure: (String) -> Unit)
    fun accept(answerSdp: String, onAccepted: () -> Unit, onFailure: (String) -> Unit)
    fun setMuted(muted: Boolean)
    fun setSpeakerMuted(muted: Boolean)
    fun close()
}

/** What a [NativeLiveCallPeer] reports. A peer starts out connecting; failed and closed are final. */
interface NativeLiveCallEvents {
    fun connected()
    fun connecting()
    fun failed()
    fun closed()
    fun levels(input: Float, output: Float)
}

fun liveCallPeer(native: NativeLiveCallPeer): LiveCallPeer = CallbackLiveCallPeer(native)

private class CallbackLiveCallPeer(private val native: NativeLiveCallPeer) : LiveCallPeer {
    override val state: StateFlow<LivePeerState>
        field = MutableStateFlow(LivePeerState.Connecting)
    override val levels: StateFlow<LiveCallLevels>
        field = MutableStateFlow(LiveCallLevels())

    init {
        native.attach(object : NativeLiveCallEvents {
            override fun connected() = advance(LivePeerState.Connected)
            override fun connecting() = advance(LivePeerState.Connecting)
            override fun failed() = advance(LivePeerState.Failed)
            override fun closed() = advance(LivePeerState.Closed)
            override fun levels(input: Float, output: Float) {
                if (state.value != LivePeerState.Closed) levels.value = LiveCallLevels(input.coerceIn(0f, 1f), output.coerceIn(0f, 1f))
            }
        })
    }

    private fun advance(next: LivePeerState) = state.update { it.advancedTo(next) }

    override suspend fun createOffer(): String = suspendCancellableCoroutine { c ->
        native.createOffer({ if (c.isActive) c.resume(it) }, { if (c.isActive) c.resumeWithException(LiveCallException(it)) })
    }

    override suspend fun accept(answerSdp: String) = suspendCancellableCoroutine { c ->
        native.accept(answerSdp, { if (c.isActive) c.resume(Unit) }, { if (c.isActive) c.resumeWithException(LiveCallException(it)) })
    }

    override fun setMuted(muted: Boolean) = native.setMuted(muted)

    override fun setSpeakerMuted(muted: Boolean) = native.setSpeakerMuted(muted)

    @Volatile private var closing = false

    override fun close() {
        if (closing) return
        closing = true
        advance(LivePeerState.Closed)
        levels.value = LiveCallLevels()
        native.close()
    }
}
