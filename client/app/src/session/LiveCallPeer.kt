package kinuvo.app.session

import kinuvo.protocol.voice.LivePeerState
import kotlinx.coroutines.flow.StateFlow

/** Microphone and speaker loudness in 0..1. */
data class LiveCallLevels(val input: Float = 0f, val output: Float = 0f)

/** Why a [LiveCallPeer] can't carry a call; [message] is fit to show the user. */
class LiveCallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The Device's end of a call it holds: one WebRTC connection carrying one audio track each way, with the platform's
 * echo cancellation, noise suppression and gain control. The Server brokers it over [kinuvo.protocol.Api.LiveCallStart]
 * and only SDP crosses to it. One peer serves one call; [close] releases the microphone and speaker.
 */
interface LiveCallPeer {
    /** [LivePeerState.Connecting] until the media path is up. Failed and Closed are final. */
    val state: StateFlow<LivePeerState>
    val levels: StateFlow<LiveCallLevels>
    /**
     * The complete offer: max-bundle, all ICE candidates gathered (no trickle) and a single send+receive audio track.
     * No data channel is needed. Throws [LiveCallException] when there is no microphone or speaker or the network is unreachable.
     */
    suspend fun createOffer(): String
    suspend fun accept(answerSdp: String)
    /** Stops sending the microphone at once; the connection stays up. */
    fun setMuted(muted: Boolean)
    /** Silences what the model says without ending it. */
    fun setSpeakerMuted(muted: Boolean)
    fun close()
}
