package kinuvo.protocol.session

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agui.AgUiEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One change to a Session, pushed in order to the clients subscribed to it. */
@Serializable
data class SessionUpdate(val agentId: AgentId, val sessionId: SessionId, val change: SessionChange)

/**
 * What a Session subscription sends: one [Snapshot], then frames that are either an AG-UI event of the active run or a
 * Kinuvo item (ADR 0042).
 */
@Serializable
sealed interface SessionChange {
    /** Everything held at one cutoff; the frames that follow start right after it. */
    @Serializable @SerialName("snapshot")
    data class Snapshot(val snapshot: SessionSnapshot) : SessionChange

    /** A persisted entry; [seq] is its position in the Session. */
    @Serializable @SerialName("entry")
    data class Entry(val seq: Int, val entry: SessionEntry) : SessionChange

    /** One AG-UI 1.0 event of the active run. */
    @Serializable @SerialName("run")
    data class Run(val event: AgUiEvent) : SessionChange

    /** A change with no AG-UI counterpart: queue, children, interactions, wake, Reflection, notice or a run-less status. */
    @Serializable @SerialName("item")
    data class Item(val event: AgentEvent) : SessionChange
}

/**
 * The Session at one cutoff: the persisted entries from [firstSeq] up to [seq] (the position of the next entry), what
 * is queued, delegated or waiting for an answer in [state], and the AG-UI events of the active run since its
 * `RUN_STARTED` in [run]. Run items already in [entries] are skipped by the client. A page or run too large for one frame
 * puts only its first entries and events in the Snapshot; the rest of the entries up to [seq] follow as
 * [SessionChange.Entry] frames, then the rest of the run as [SessionChange.Run] frames, before anything live.
 */
@Serializable
data class SessionSnapshot(
    val firstSeq: Int,
    val seq: Int,
    val entries: List<SessionEntry>,
    val state: LiveState = LiveState(),
    val run: List<AgUiEvent> = emptyList(),
)
