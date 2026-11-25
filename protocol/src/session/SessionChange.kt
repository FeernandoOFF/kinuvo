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

    /** What did not fit in the [Snapshot], in bounded frames: the persisted [entries] from [firstSeq] on, or events of the active [run], never both in one frame. */
    @Serializable @SerialName("continued")
    data class Continued(val firstSeq: Int = 0, val entries: List<SessionEntry> = emptyList(), val run: List<AgUiEvent> = emptyList()) : SessionChange

    /** A change with no AG-UI counterpart: queue, children, interactions, wake, Reflection, notice or a run-less status. */
    @Serializable @SerialName("item")
    data class Item(val event: AgentEvent) : SessionChange
}

/**
 * The Session at one cutoff: the persisted entries from [firstSeq] up to [seq] (the position of the next entry), what
 * is queued, delegated or waiting for an answer in [state], and the AG-UI events of the active run since its
 * `RUN_STARTED` in [run]. Run items already in [entries] are skipped by the client. A page or run too large for one frame
 * puts only its first entries and events in the Snapshot; the rest of the entries up to [seq] follow in
 * [SessionChange.Continued] frames, then the rest of the run, before anything live.
 */
@Serializable
data class SessionSnapshot(
    val firstSeq: Int,
    val seq: Int,
    val entries: List<SessionEntry>,
    val state: LiveState = LiveState(),
    val run: List<AgUiEvent> = emptyList(),
)
