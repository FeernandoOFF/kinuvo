package kinuvo.protocol.session

import kinuvo.protocol.Api
import kinuvo.protocol.routine.Routine
import kinuvo.protocol.surface.SurfaceAction
import kinuvo.protocol.agent.AgentId
import kotlinx.serialization.Serializable

/** [voice] marks [text] as transcribed from voice chat. */
@Serializable
data class SendParams(val agentId: AgentId, val sessionId: SessionId? = null, val text: String, val attachments: List<String> = emptyList(), val voice: Boolean = false, val submissionId: String? = null, val action: SurfaceAction? = null, val delivery: SendDelivery = SendDelivery.Turn, val discovery: Boolean = false)

@Serializable
enum class SendDelivery { Turn, Queue, Steer }

@Serializable
data class QueuedMessage(val id: String, val text: String, val attachments: List<Attachment> = emptyList())

@Serializable
data class CancelQueuedParams(val agentId: AgentId, val sessionId: SessionId, val id: String)

/** [activation] is set when the text was a `/skill` command; [message] is what was sent to the model. */
@Serializable
data class SendResult(val sessionId: SessionId, val runId: RunId, val message: String, val activation: SessionEntry.Activation? = null)

/** Replaces the Session's [SessionSettings]; they apply from its next run. */
@Serializable
data class ConfigureSessionParams(val agentId: AgentId, val sessionId: SessionId, val settings: SessionSettings)

/** Renames the Session (blank: back to its first message) or marks it a favorite; null fields stay as they are. */
@Serializable
data class UpdateSessionParams(
    val agentId: AgentId,
    val sessionId: SessionId,
    val title: String? = null,
    val favorite: Boolean? = null,
    val settled: Boolean? = null,
    /** True marks the Session read. */
    val seen: Boolean? = null,
)

@Serializable
data class StopParams(val sessionId: SessionId)

/** [before] is the last Session ID of the previous page. Lists the user's Sessions, or with [routineId] that Routine's. */
@Serializable
data class SessionsParams(val agentId: AgentId, val before: SessionId? = null, val limit: Int = 30, val routineId: String? = null)

@Serializable
data class SessionParams(val agentId: AgentId, val sessionId: SessionId)

/**
 * Subscribes to a Session: the server sends a [SessionChange.Snapshot] as an [Api.Update], then frames until
 * [Api.Unsubscribe]. The snapshot holds the entries from [after] on, or only the last [limit] when there is no [after]
 * or more than [limit] entries are behind; older ones load with [Api.History].
 */
@Serializable
data class SubscribeParams(val agentId: AgentId, val sessionId: SessionId, val after: Int? = null, val limit: Int = 100)

@Serializable
data class UnsubscribeParams(val sessionId: SessionId)

/** Up to [limit] entries before seq [before]. */
@Serializable
data class HistoryParams(val agentId: AgentId, val sessionId: SessionId, val before: Int, val limit: Int = 100)
