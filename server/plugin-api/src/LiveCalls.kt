package kinuvo.server.plugin

/**
 * A live audio conversation whose media connection (WebRTC) the Device holds, so the Server never carries audio. The
 * Server creates the call with the vendor using credentials the Device never sees, then controls it through a side
 * channel. One adapter per vendor protocol. Agent work goes through [LiveCallEvent.Delegation].
 */
interface LiveCallProvider {
    /** Whether a call could start now, e.g. the owner is signed in. */
    suspend fun available(): Boolean

    /** Creates the call for the Device's WebRTC [offerSdp]; throws [LiveCallException] with a message fit for the Device. */
    suspend fun start(context: VoiceContext, offerSdp: String): LiveCallStart
}

/** [answerSdp] goes back to the Device; [call] is then run once the Device applies it. */
class LiveCallStart(val answerSdp: String, val call: LiveCall)

interface LiveCall {
    /** Attaches the side channel and runs until the call ends, passing its events to [onEvent]; throws [LiveCallException] when it fails. */
    suspend fun run(onEvent: suspend (LiveCallEvent) -> Unit)

    /** Gives the model [text] about delegation [id], whether progress or the result. */
    suspend fun reply(id: String, text: String)

    /** Tells the model something happened outside the call, so it can mention it. */
    suspend fun notify(text: String)
    suspend fun pause()
    suspend fun resume()

    /** Asks the provider to close the call; [run] returns once it has. */
    suspend fun end()
}

sealed interface LiveCallEvent {
    data object Ready : LiveCallEvent
    data object Listening : LiveCallEvent
    data object Speaking : LiveCallEvent
    /** The model hands [task] to the Agent and waits for [LiveCall.reply]. */
    data class Delegation(val id: String, val task: String) : LiveCallEvent
    /** What has been said so far in turn [item]; the [final] transcript replaces the partial ones. */
    data class Transcript(val item: String, val text: String, val user: Boolean, val final: Boolean = true) : LiveCallEvent
}

/** What a conversation starts from: who the model speaks for, and the Session so far, oldest first. */
class VoiceContext(val instructions: String, val history: List<VoiceMessage> = emptyList())

data class VoiceMessage(val user: Boolean, val text: String)

sealed interface AgentProgress {
    data class ApprovalRequested(val tool: String, val summary: String) : AgentProgress
    data class ApprovalResolved(val allowed: Boolean?) : AgentProgress
    data class Question(val text: String) : AgentProgress
    data class Text(val text: String) : AgentProgress
}

/** A call failure worded for the Device; the raw detail belongs in the Server log. */
class LiveCallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the model is told while the Agent works on a delegation. */
fun AgentProgress.voiceText(): String = when (this) {
    is AgentProgress.ApprovalRequested -> "Waiting for the user's approval on screen to run ${tool.take(80)}: ${summary.take(240)}. Tell the user to review it in the app. Approvals must only be answered on screen, never by voice."
    is AgentProgress.ApprovalResolved -> when (allowed) {
        true -> "The user allowed the action. The Agent is continuing."
        false -> "The user denied the action."
        null -> "The approval request is no longer pending. Its decision is unavailable; do not assume it was allowed."
    }
    is AgentProgress.Question -> "The Agent asks: ${text.take(600)}. Tell the user to answer in the app."
    is AgentProgress.Text -> "Agent progress (not the final result): ${text.take(2000)}"
}
