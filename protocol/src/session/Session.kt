package kinuvo.protocol.session

import kinuvo.protocol.mcp.McpAppRef
import kinuvo.protocol.plugin.ToolIdentity
import kinuvo.protocol.reflection.ReflectionResult
import kinuvo.protocol.routine.Routine
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.surface.SurfaceAction
import kinuvo.protocol.usage.RequestMetrics
import kinuvo.protocol.agent.AgentId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionInfo(
    val id: SessionId,
    val agentId: AgentId,
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
    val parent: SessionParent? = null,
    val hidden: Boolean = false,
) {
    /** The user's own Sessions, and a Routine's once it has something for the user or waits on them. */
    val inChats: Boolean get() = !hidden && (routine == null || answered || status == SessionStatus.Waiting)
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

@Serializable
data class VoiceTurn(val user: Boolean, val text: String)

/** One line of a Session transcript. */
@Serializable
sealed interface SessionEntry {
    /** Epoch milliseconds when written; absent in legacy transcripts. */
    val writtenAt: Long?

    @Serializable @SerialName("voice_call")
    data class VoiceCall(val id: String, val turns: List<VoiceTurn>, val startedAt: Long, val durationMillis: Long, override val writtenAt: Long? = null) : SessionEntry

    @Serializable @SerialName("notice")
    data class Notice(val id: String, val code: String, val message: String, val draft: List<SessionEntry> = emptyList(), override val writtenAt: Long? = null, val child: SubAgentState? = null) : SessionEntry

    /** Instructions frozen at Session start (Identity, Soul, Memory, User Notes). */
    @Serializable @SerialName("started")
    data class Started(val instructions: String, val routine: RoutineRef? = null, override val writtenAt: Long? = null) : SessionEntry

    @Serializable @SerialName("context")
    data class Context(val content: String, override val writtenAt: Long? = null) : SessionEntry

    @Serializable @SerialName("measurement")
    data class Measurement(val metrics: RequestMetrics, override val writtenAt: Long? = null) : SessionEntry

    /** [routine] is set when a Routine sent [text] on its schedule; [voice] when [text] was transcribed from voice chat. */
    @Serializable @SerialName("user")
    data class UserMessage(val text: String, val routine: RoutineRef? = null, val attachments: List<Attachment> = emptyList(), val voice: Boolean = false, val submissionId: String? = null, override val writtenAt: Long? = null, val action: SurfaceAction? = null) : SessionEntry

    /** [messageId] matches the run's streamed events, so clients keep one item from live text to saved message. */
    @Serializable @SerialName("assistant")
    data class AssistantMessage(val text: String, val messageId: String? = null, override val writtenAt: Long? = null) : SessionEntry

    /** Reasoning summary; [encrypted] is replayed to the model so it keeps its reasoning across turns. */
    @Serializable @SerialName("reasoning")
    data class Reasoning(val text: String, val id: String? = null, val encrypted: String? = null, val messageId: String? = null, override val writtenAt: Long? = null) : SessionEntry

    @Serializable @SerialName("tool_call")
    data class ToolCall(val id: ToolCallId, val name: String, val args: String, val identity: ToolIdentity? = null, override val writtenAt: Long? = null) : SessionEntry

    /** The compressed model history; earlier transcript entries remain available to clients. */
    @Serializable @SerialName("compaction")
    data class Compaction(val summary: String = "", val history: List<SessionEntry> = emptyList(), override val writtenAt: Long? = null) : SessionEntry

    /** A background Reflection's result; it isn't part of the model's conversation. */
    @Serializable @SerialName("reflection")
    data class Reflection(val id: String, val at: Long, val result: ReflectionResult, override val writtenAt: Long? = null) : SessionEntry

    @Serializable @SerialName("reflection_review")
    data class ReflectionReview(val proposalId: String, val approved: Boolean, val note: String? = null, val result: ReflectionResult? = null, override val writtenAt: Long? = null) : SessionEntry

    /** A Skill loaded into the Session by the user (`/name`) or carried over by Compaction. */
    @Serializable @SerialName("activation")
    data class Activation(val name: String, val content: String, override val writtenAt: Long? = null) : SessionEntry

    @Serializable @SerialName("tool_report")
    data class ToolReport(val tool: String, val callId: ToolCallId?, val content: String, override val writtenAt: Long? = null) : SessionEntry

    /** The Session's settings from here on; the model doesn't see it. */
    @Serializable @SerialName("settings")
    data class Settings(val settings: SessionSettings, override val writtenAt: Long? = null) : SessionEntry

    /** The user's name for the Session (null: from its first message) and whether it's a favorite, from here on; the model doesn't see it. */
    @Serializable @SerialName("details")
    data class Details(val title: String? = null, val favorite: Boolean = false, override val writtenAt: Long? = null, val parent: SessionParent? = null, val hidden: Boolean = false) : SessionEntry

    /** How a Routine's run ended: [report] when it has [summary] for the user, else nothing was new; the model doesn't see it. */
    @Serializable @SerialName("outcome")
    data class Outcome(val report: Boolean, val summary: String = "", override val writtenAt: Long? = null) : SessionEntry

    /** [attachments] are files the tool shows the model, e.g. an image from a note. */
    @Serializable @SerialName("tool_result")
    data class ToolResult(val id: ToolCallId, val name: String, val output: String, val isError: Boolean = false, val attachments: List<Attachment> = emptyList(), val identity: ToolIdentity? = null, override val writtenAt: Long? = null, val app: McpAppRef? = null, val appResult: JsonObject? = null) : SessionEntry
}

/** Shared by persisted listings and clients; legacy UUIDs carry no creation time. */
val SessionOrder: Comparator<SessionInfo> = compareByDescending<SessionInfo> { it.createdAt != null }
    .thenByDescending { it.createdAt }
    .thenBy { it.legacyOrder ?: Long.MAX_VALUE }
    .thenBy { it.id.value }

/** Type is part of identity: a tool call and its result deliberately share a tool ID. */
@Serializable
data class SessionEntryIdentity(val type: String, val id: String)

fun SessionEntry.identity(): SessionEntryIdentity? = when (this) {
    is SessionEntry.VoiceCall -> SessionEntryIdentity("voice_call", id)
    is SessionEntry.UserMessage -> submissionId?.let { SessionEntryIdentity("user", it) }
    is SessionEntry.AssistantMessage -> messageId?.let { SessionEntryIdentity("assistant", it) }
    is SessionEntry.Reasoning -> (messageId ?: id)?.let { SessionEntryIdentity("reasoning", it) }
    is SessionEntry.ToolCall -> SessionEntryIdentity("tool_call", id.value)
    is SessionEntry.ToolResult -> SessionEntryIdentity("tool_result", id.value)
    is SessionEntry.Notice -> SessionEntryIdentity("notice", id)
    else -> null
}

fun SessionEntry.withWrittenAt(at: Long?): SessionEntry = when (this) {
    is SessionEntry.VoiceCall -> copy(writtenAt = at)
    is SessionEntry.Notice -> copy(writtenAt = at)
    is SessionEntry.Started -> copy(writtenAt = at)
    is SessionEntry.Context -> copy(writtenAt = at)
    is SessionEntry.Measurement -> copy(writtenAt = at)
    is SessionEntry.UserMessage -> copy(writtenAt = at)
    is SessionEntry.AssistantMessage -> copy(writtenAt = at)
    is SessionEntry.Reasoning -> copy(writtenAt = at)
    is SessionEntry.ToolCall -> copy(writtenAt = at)
    is SessionEntry.Compaction -> copy(writtenAt = at)
    is SessionEntry.Reflection -> copy(writtenAt = at)
    is SessionEntry.ReflectionReview -> copy(writtenAt = at)
    is SessionEntry.Activation -> copy(writtenAt = at)
    is SessionEntry.ToolReport -> copy(writtenAt = at)
    is SessionEntry.Settings -> copy(writtenAt = at)
    is SessionEntry.Details -> copy(writtenAt = at)
    is SessionEntry.Outcome -> copy(writtenAt = at)
    is SessionEntry.ToolResult -> copy(writtenAt = at)
}
