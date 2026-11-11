package kinuvo.protocol.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One change to a Session, pushed in order to the clients subscribed to it. */
@Serializable
data class SessionUpdate(val agentId: String, val sessionId: String, val change: SessionChange)

@Serializable
sealed interface SessionChange {
    /** Atomically replaces a mutable transcript window and its live state. */
    @Serializable @SerialName("window")
    data class Window(val firstSeq: Int, val entries: List<SessionEntry>, val state: LiveState) : SessionChange

    /** A persisted entry; [seq] is its position in the Session. */
    @Serializable @SerialName("entry")
    data class Entry(val seq: Int, val entry: SessionEntry) : SessionChange

    /** A run event not covered by entries, e.g. a text delta. */
    @Serializable @SerialName("event")
    data class Event(val event: AgentEvent) : SessionChange

    /** The catch-up starts a fresh window at [firstSeq]; drop what was held before. */
    @Serializable @SerialName("reset")
    data class Reset(val firstSeq: Int) : SessionChange

    /** Replaces the client's live state; sent at the end of a catch-up. */
    @Serializable @SerialName("live")
    data class Live(val state: LiveState) : SessionChange
}
