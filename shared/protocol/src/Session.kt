package kinuvo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionInfo(
    val id: String,
    val agentId: String,
    val title: String,
    val status: SessionStatus = SessionStatus.Idle,
    /** The Routine that started this Session; null for Sessions the user started. */
    val routine: RoutineRef? = null,
    val settings: SessionSettings = SessionSettings(),
    /** Whether a Routine's run reported to the user, or the user joined in. */
    val answered: Boolean = false,
    /** The Task this Session is a run of; null for other Sessions. */
    val task: String? = null,
    /** Listed under Favorites in the sidebar. */
    val favorite: Boolean = false,
    /** Epoch milliseconds, absent for legacy Sessions with no known creation time. */
    val createdAt: Long? = null,
    /** Frozen migration ordinal, in the original newest-first order. */
    val legacyOrder: Long? = null,
) {
    /** The user's own Sessions, and a Routine's once it has something for the user or waits on them. */
    val inChats: Boolean get() = routine == null || answered || status == SessionStatus.Waiting
}

/** A Task run's prompt: this first line, then the Task's details. */
fun taskPrompt(id: String, title: String) = "[Task $id] $title"

/** The Task ID and title a Task run's prompt starts with, or null for other messages. */
fun parseTaskPrompt(text: String): Pair<String, String>? =
    TASK_PROMPT.find(text)?.let { it.groupValues[1] to it.groupValues[2].trim() }

private val TASK_PROMPT = Regex("""^\[Task ([^\]]+)] ([^\n]*)""")

/** A Session's own model, reasoning effort and risk tolerance; null fields follow the Agent's. */
@Serializable
data class SessionSettings(val model: String? = null, val reasoning: String? = null, val approvals: String? = null, val provider: String? = null)

/** [Waiting]: a run is paused on an Approval or an `ask_user` form. */
@Serializable
enum class SessionStatus { Idle, Running, Waiting }

/** One page of an Agent's Sessions, newest first; the first page of chats also has every favorite in [favorites]. */
@Serializable
data class SessionPage(val sessions: List<SessionInfo>, val hasMore: Boolean, val favorites: List<SessionInfo> = emptyList())

/** One line of a Session transcript. */
@Serializable
sealed interface SessionEntry {
    @Serializable @SerialName("notice")
    data class Notice(val id: String, val code: String, val message: String, val draft: List<SessionEntry> = emptyList()) : SessionEntry

    /** Instructions frozen at Session start (Identity, Soul, Memory, User Notes). */
    @Serializable @SerialName("started")
    data class Started(val instructions: String, val routine: RoutineRef? = null) : SessionEntry

    @Serializable @SerialName("context")
    data class Context(val content: String) : SessionEntry

    @Serializable @SerialName("measurement")
    data class Measurement(val metrics: RequestMetrics) : SessionEntry

    /** [routine] is set when a Routine sent [text] on its schedule; [voice] when [text] was transcribed from voice chat. */
    @Serializable @SerialName("user")
    data class UserMessage(val text: String, val routine: RoutineRef? = null, val attachments: List<Attachment> = emptyList(), val voice: Boolean = false, val submissionId: String? = null) : SessionEntry

    /** [messageId] matches the run's streamed events, so clients keep one item from live text to saved message. */
    @Serializable @SerialName("assistant")
    data class AssistantMessage(val text: String, val messageId: String? = null) : SessionEntry

    /** Reasoning summary; [encrypted] is replayed to the model so it keeps its reasoning across turns. */
    @Serializable @SerialName("reasoning")
    data class Reasoning(val text: String, val id: String? = null, val encrypted: String? = null, val messageId: String? = null) : SessionEntry

    @Serializable @SerialName("tool_call")
    data class ToolCall(val id: String, val name: String, val args: String, val identity: ToolIdentity? = null) : SessionEntry

    /** The compressed model history; earlier transcript entries remain available to clients. */
    @Serializable @SerialName("compaction")
    data class Compaction(val summary: String = "", val history: List<SessionEntry> = emptyList()) : SessionEntry

    /** A background Reflection's result; it isn't part of the model's conversation. */
    @Serializable @SerialName("reflection")
    data class Reflection(val id: String, val at: Long, val result: ReflectionResult) : SessionEntry

    @Serializable @SerialName("reflection_review")
    data class ReflectionReview(val proposalId: String, val approved: Boolean, val note: String? = null, val result: ReflectionResult? = null) : SessionEntry

    /** A Skill loaded into the Session by the user (`/name`) or carried over by Compaction. */
    @Serializable @SerialName("activation")
    data class Activation(val name: String, val content: String) : SessionEntry

    /** The Session's settings from here on; the model doesn't see it. */
    @Serializable @SerialName("settings")
    data class Settings(val settings: SessionSettings) : SessionEntry

    /** The user's name for the Session (null: from its first message) and whether it's a favorite, from here on; the model doesn't see it. */
    @Serializable @SerialName("details")
    data class Details(val title: String? = null, val favorite: Boolean = false) : SessionEntry

    /** How a Routine's run ended: [report] when it has [summary] for the user, else nothing was new; the model doesn't see it. */
    @Serializable @SerialName("outcome")
    data class Outcome(val report: Boolean, val summary: String = "") : SessionEntry

    /** [attachments] are files the tool shows the model, e.g. an image from a note. */
    @Serializable @SerialName("tool_result")
    data class ToolResult(val id: String, val name: String, val output: String, val isError: Boolean = false, val attachments: List<Attachment> = emptyList(), val identity: ToolIdentity? = null) : SessionEntry
}

/** Shared by persisted listings and clients; legacy UUIDs carry no creation time. */
val SessionOrder: Comparator<SessionInfo> = compareByDescending<SessionInfo> { it.createdAt != null }
    .thenByDescending { it.createdAt }
    .thenBy { it.legacyOrder ?: Long.MAX_VALUE }
    .thenBy { it.id }

/** Type is part of identity: a tool call and its result deliberately share a tool ID. */
@Serializable
data class SessionEntryIdentity(val type: String, val id: String)

fun SessionEntry.identity(): SessionEntryIdentity? = when (this) {
    is SessionEntry.UserMessage -> submissionId?.let { SessionEntryIdentity("user", it) }
    is SessionEntry.AssistantMessage -> messageId?.let { SessionEntryIdentity("assistant", it) }
    is SessionEntry.Reasoning -> (messageId ?: id)?.let { SessionEntryIdentity("reasoning", it) }
    is SessionEntry.ToolCall -> SessionEntryIdentity("tool_call", id)
    is SessionEntry.ToolResult -> SessionEntryIdentity("tool_result", id)
    is SessionEntry.Notice -> SessionEntryIdentity("notice", id)
    else -> null
}
