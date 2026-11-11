package kinuvo.protocol.session

import kinuvo.protocol.Api
import kinuvo.protocol.routine.Routine
import kinuvo.protocol.surface.SurfaceAction
import kotlinx.serialization.Serializable

/** [voice] marks [text] as transcribed from voice chat. */
@Serializable
data class SendParams(val agentId: String, val sessionId: String? = null, val text: String, val attachments: List<String> = emptyList(), val voice: Boolean = false, val submissionId: String? = null, val action: SurfaceAction? = null)

/** [activation] is set when the text was a `/skill` command; [message] is what was sent to the model. */
@Serializable
data class SendResult(val sessionId: String, val runId: String, val message: String, val activation: SessionEntry.Activation? = null)

/** Replaces the Session's [SessionSettings]; they apply from its next run. */
@Serializable
data class ConfigureSessionParams(val agentId: String, val sessionId: String, val settings: SessionSettings)

/** Renames the Session (blank: back to its first message) or marks it a favorite; null fields stay as they are. */
@Serializable
data class UpdateSessionParams(val agentId: String, val sessionId: String, val title: String? = null, val favorite: Boolean? = null)

@Serializable
data class StopParams(val sessionId: String)

/** [before] is the last Session ID of the previous page. Lists the user's Sessions, or with [routineId] that Routine's. */
@Serializable
data class SessionsParams(val agentId: String, val before: String? = null, val limit: Int = 30, val routineId: String? = null)

@Serializable
data class SessionParams(val agentId: String, val sessionId: String)

/**
 * Subscribes to a Session: the server sends, as [Api.Update]s, the entries from [after] on and then the live state,
 * followed by live updates until [Api.Unsubscribe]. With no [after], or more than [limit] entries behind, it sends
 * [SessionChange.Reset] and only the last [limit] entries; older ones load with [Api.History].
 */
@Serializable
data class CatchUpParams(val agentId: String, val sessionId: String, val after: Int? = null, val limit: Int = 100)

@Serializable
data class UnsubscribeParams(val sessionId: String)

/** Up to [limit] entries before seq [before]. */
@Serializable
data class HistoryParams(val agentId: String, val sessionId: String, val before: Int, val limit: Int = 100)
