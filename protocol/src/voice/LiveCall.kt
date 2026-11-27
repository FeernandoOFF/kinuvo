package kinuvo.protocol.voice

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Starts a live call in a Session where the Device holds the WebRTC connection (microphone and speaker) and the Server
 * brokers it: it creates the call with its provider and controls it, so no audio and no provider credentials cross this API.
 * The Device sends its complete (all ICE candidates gathered, max-bundle) [offerSdp].
 */
@Serializable
data class LiveCallParams(val agentId: AgentId, val sessionId: SessionId, val offerSdp: String)

/** The Device applies [answerSdp] as its remote description, then reports [LivePeerState.Connecting]. */
@Serializable
data class LiveCallAnswer(val callId: String, val answerSdp: String)

@Serializable
enum class LivePeerState { Connecting, Connected, Failed, Closed }

/** The Device's WebRTC connection state; the Server controls the call only once it has reported [LivePeerState.Connecting] or [LivePeerState.Connected]. */
@Serializable
data class LivePeerParams(val callId: String, val state: LivePeerState)

@Serializable
enum class LiveCallPhase { Listening, Speaking, Thinking, Paused }

/** What the Server tells the Device about a call it started: the transcript lands in the Session when it ends. */
@Serializable
sealed interface LiveCallUpdate {
    val callId: String
    /** The call is attached and live; [LiveCallPhase.Thinking] while the Agent works on a delegated task. */
    @Serializable @SerialName("phase") data class Phase(override val callId: String, val phase: LiveCallPhase) : LiveCallUpdate
    /** What has been said so far in turn [item]; the [final] transcript replaces the partial ones. */
    @Serializable @SerialName("transcript") data class Transcript(override val callId: String, val item: String, val text: String, val user: Boolean, val final: Boolean = true) : LiveCallUpdate
    /** The call is over, with [error] when it failed; the Device closes its connection. */
    @Serializable @SerialName("ended") data class Ended(override val callId: String, val error: String? = null) : LiveCallUpdate
}
