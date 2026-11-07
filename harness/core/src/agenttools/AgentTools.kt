package kinuvo.harness.core.agenttools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.requireString
import kinuvo.harness.core.tools.string
import kinuvo.protocol.ExecutionKind
import kinuvo.protocol.LiveState
import kinuvo.protocol.Risk
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * Who calls a Kinuvo tool, whether natively or over MCP. Session tools only ever see [agentId]'s own Sessions.
 * [depth] counts the delegations above the caller.
 */
data class CallerScope(val agentId: String, val sessionId: String?, val depth: Int = 0)

data class AgentSummary(
    val id: String,
    val name: String,
    val description: String,
    val execution: ExecutionKind,
    val provider: String?,
    val model: String?,
    val onDevice: Boolean,
)

/** What the tools read; the Server supplies it. */
interface AgentToolsSource {
    /** Agents the caller may work with: native and External, never ones reached through an execution grant. */
    suspend fun agents(): List<AgentSummary>
    suspend fun sessions(agentId: String): List<SessionInfo>
    suspend fun entries(agentId: String, sessionId: String): List<SessionEntry>
    suspend fun live(sessionId: String): LiveState
}

/**
 * One tools service for every transport: native Agents get these as built-in tools, External Agents reach them over
 * MCP. Each call carries its [CallerScope]. Hidden Sessions never show up, for any caller.
 */
class AgentTools(private val source: AgentToolsSource) {
    fun tools(scope: CallerScope): List<Tool> = listOf(
        AgentsList(source, scope), SessionsList(source, scope), SessionsSearch(source, scope), SessionsRead(source, scope), SessionStatusTool(source, scope),
    )
}

private abstract class ReadTool(protected val source: AgentToolsSource, protected val scope: CallerScope) : Tool {
    override val risk = Risk.Low

    /** The caller's own visible Session [id]; any other is as good as missing. */
    protected suspend fun own(id: String): SessionInfo =
        requireNotNull(source.sessions(scope.agentId).firstOrNull { it.id == id && !it.hidden }) { "No Session '$id'" }

    protected suspend fun visible() = source.sessions(scope.agentId).filter { !it.hidden }

    // Some providers send every optional parameter, blank when unused.
    protected fun JsonObject.clean() = JsonObject(filterValues { (it as? JsonPrimitive)?.content?.isNotBlank() != false })
    protected fun JsonObject.int(key: String) = string(key)?.let { requireNotNull(it.toIntOrNull()) { "'$key' must be a number" } }
    protected fun JsonObject.limit(default: Int, max: Int) = (int("limit") ?: default).coerceIn(1, max)
    protected fun JsonObject.since() = string("since")?.let { parseTime(it) }
}

private class AgentsList(source: AgentToolsSource, scope: CallerScope) : ReadTool(source, scope) {
    override val descriptor = ToolDescriptor(
        name = "agents_list",
        description = "The Agents you can work with: id, name, what it does, where it runs and on which model provider.",
    )

    override suspend fun execute(args: JsonObject): String = source.agents().filter { it.execution != ExecutionKind.Remote }.joinToString("\n") { a ->
        val where = if (a.execution == ExecutionKind.Native) "native" else "external"
        val model = listOfNotNull(a.provider, a.model).joinToString("/").ifEmpty { null }
        "- ${a.id} \"${a.name}\"${if (a.id == scope.agentId) " (you)" else ""}: ${a.description.ifBlank { "no description" }} [$where${model?.let { ", $it" }.orEmpty()}${if (a.onDevice) ", on-device" else ""}]"
    }.ifEmpty { "No other Agents." }
}

private class SessionsList(source: AgentToolsSource, scope: CallerScope) : ReadTool(source, scope) {
    override val descriptor = ToolDescriptor(
        name = "sessions_list",
        description = "Your own past Sessions, newest first. Read one with sessions_read, or search their content with sessions_search.",
        optionalParameters = listOf(
            ToolParameterDescriptor("query", "Only Sessions whose title contains this", ToolParameterType.String),
            ToolParameterDescriptor("since", "Only Sessions created since this date or time, e.g. 2026-11-01 or 2026-11-01T09:00:00Z", ToolParameterType.String),
            ToolParameterDescriptor("limit", "How many to return (default $DEFAULT_LIST, at most $MAX_LIST)", ToolParameterType.String),
            ToolParameterDescriptor("cursor", "The next_cursor of the previous page", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = args.clean()
        val since = a.since()
        val query = a.string("query")?.lowercase()
        val limit = a.limit(DEFAULT_LIST, MAX_LIST)
        val matching = visible().filter { s -> (query == null || query in s.title.lowercase()) && (since == null || (s.createdAt ?: 0) >= since) }
        val from = a.string("cursor")?.let { id -> matching.indexOfFirst { it.id == id }.also { require(it >= 0) { "Unknown cursor" } } + 1 } ?: 0
        val page = matching.drop(from).take(limit)
        if (page.isEmpty()) return "No Sessions."
        return page.joinToString("\n") { s -> "- ${s.id} \"${s.title}\" [${s.status.name.lowercase()}]${s.createdAt?.let { ", created ${time(it)}" }.orEmpty()}" } +
            if (from + page.size < matching.size) "\nnext_cursor: ${page.last().id}" else ""
    }
}

private class SessionsSearch(source: AgentToolsSource, scope: CallerScope) : ReadTool(source, scope) {
    override val descriptor = ToolDescriptor(
        name = "sessions_search",
        description = "Search what was said in your own past Sessions (user and assistant messages, voice calls). All words must appear in the same message. " +
            "Searches the newest $MAX_SCANNED Sessions. Each hit is a Session id and message ref for sessions_read.",
        requiredParameters = listOf(ToolParameterDescriptor("query", "Words to find", ToolParameterType.String)),
        optionalParameters = listOf(
            ToolParameterDescriptor("session_id", "Search only this Session", ToolParameterType.String),
            ToolParameterDescriptor("since", "Only Sessions created since this date or time", ToolParameterType.String),
            ToolParameterDescriptor("limit", "How many hits (default $DEFAULT_HITS, at most $MAX_HITS)", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = args.clean()
        val words = a.requireString("query").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val limit = a.limit(DEFAULT_HITS, MAX_HITS)
        val since = a.since()
        val sessions = a.string("session_id")?.let { listOf(own(it)) }
            ?: visible().filter { since == null || (it.createdAt ?: 0) >= since }.take(MAX_SCANNED)
        val hits = mutableListOf<String>()
        for (session in sessions) {
            for ((ref, message) in source.entries(scope.agentId, session.id).messages().filter { it.second.role != "tool" }) {
                val text = message.text
                if (words.all { it in text.lowercase() }) hits += "${session.id} \"${session.title}\" #$ref ${message.role}: ${excerpt(text, words.first())}"
                if (hits.size == limit) return hits.joinToString("\n")
            }
        }
        return hits.joinToString("\n").ifEmpty { "No matches." }
    }
}

private class SessionsRead(source: AgentToolsSource, scope: CallerScope) : ReadTool(source, scope) {
    override val descriptor = ToolDescriptor(
        name = "sessions_read",
        description = "A page of one of your own Sessions' messages, oldest first. By default the last ones; page with before/after, or jump to a message ref from sessions_search with around.",
        requiredParameters = listOf(ToolParameterDescriptor("session_id", "The Session's id", ToolParameterType.String)),
        optionalParameters = listOf(
            ToolParameterDescriptor("around", "A message ref (#n): read the messages around it", ToolParameterType.String),
            ToolParameterDescriptor("before", "A message ref: read the messages before it", ToolParameterType.String),
            ToolParameterDescriptor("after", "A message ref: read the messages after it", ToolParameterType.String),
            ToolParameterDescriptor("from", "start: read from the first message instead of the last", ToolParameterType.String),
            ToolParameterDescriptor("limit", "How many messages (default $DEFAULT_READ, at most $MAX_READ)", ToolParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = args.clean()
        val session = own(a.requireString("session_id"))
        val limit = a.limit(DEFAULT_READ, MAX_READ)
        val all = source.entries(scope.agentId, session.id).messages()
        fun ref(key: String) = a.string(key)?.removePrefix("#")?.let { requireNotNull(it.toIntOrNull()) { "'$key' must be a message ref like #12" } }
        val page = when {
            ref("around") != null -> { val i = all.indexOfFirst { it.first >= ref("around")!! }.let { if (it < 0) all.size else it }; all.drop(maxOf(0, i - limit / 2)).take(limit) }
            ref("before") != null -> all.filter { it.first < ref("before")!! }.takeLast(limit)
            ref("after") != null -> all.filter { it.first > ref("after")!! }.take(limit)
            a.string("from") == "start" -> all.take(limit)
            else -> all.takeLast(limit)
        }
        if (page.isEmpty()) return "No messages."
        return buildString {
            append("${session.id} \"${session.title}\"\n")
            page.forEach { (ref, m) -> append("#$ref ${m.role}: ${m.text.take(MAX_MESSAGE)}${if (m.text.length > MAX_MESSAGE) "…" else ""}\n") }
            if (page.first().first != all.first().first) append("earlier: before=#${page.first().first}\n")
            if (page.last().first != all.last().first) append("later: after=#${page.last().first}")
        }.trimEnd()
    }
}

private class SessionStatusTool(source: AgentToolsSource, scope: CallerScope) : ReadTool(source, scope) {
    override val descriptor = ToolDescriptor(
        name = "session_status",
        description = "Where one of your own Sessions stands: running or idle, model, usage so far and what it waits on.",
        optionalParameters = listOf(ToolParameterDescriptor("session_id", "The Session's id; defaults to the current one", ToolParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val session = own(args.clean().string("session_id") ?: requireNotNull(scope.sessionId) { "Missing 'session_id'" })
        val live = source.live(session.id)
        val entries = source.entries(scope.agentId, session.id)
        val metrics = entries.filterIsInstance<SessionEntry.Measurement>().map { it.metrics }
        val waiting = listOfNotNull(
            live.approval?.let { "approval for ${it.toolCallName}" },
            live.questions?.let { "an answer to the user's form" },
            live.pending.takeIf { it.isNotEmpty() }?.let { "${it.size} request(s) from the harness" },
        )
        return buildList {
            add("${session.id} \"${session.title}\"")
            add("state: ${live.status.name.lowercase()}${if (live.status == SessionStatus.Waiting) ", waiting for the user: ${waiting.joinToString()}" else ""}")
            session.settings.provider?.let { add("provider: $it") }
            (session.settings.model ?: metrics.lastOrNull()?.model)?.let { add("model: $it") }
            add("usage: ${metrics.size} request(s), ${metrics.sumOf { it.inputTokens ?: 0 }} input and ${metrics.sumOf { it.outputTokens ?: 0 }} output tokens")
            add("messages: ${entries.messages().size}")
        }.joinToString("\n")
    }
}

private const val DEFAULT_LIST = 20
private const val MAX_LIST = 50
private const val DEFAULT_HITS = 10
private const val MAX_HITS = 25
private const val MAX_SCANNED = 100
private const val DEFAULT_READ = 20
private const val MAX_READ = 50
private const val MAX_MESSAGE = 2_000
private const val EXCERPT = 80

private class Message(val role: String, val text: String)

/** The transcript's messages with their refs (positions in the transcript); tool use shows only as the tool's name. */
private fun List<SessionEntry>.messages(): List<Pair<Int, Message>> = withIndex().mapNotNull { (i, e) ->
    when (e) {
        is SessionEntry.UserMessage -> Message("user", e.text)
        is SessionEntry.AssistantMessage -> Message("assistant", e.text)
        is SessionEntry.VoiceCall -> Message("voice", e.turns.joinToString(" ") { "${if (it.user) "user" else "assistant"}: ${it.text}" })
        is SessionEntry.ToolCall -> Message("tool", "used ${e.name}")
        else -> null
    }?.takeIf { it.text.isNotBlank() }?.let { i to it }
}

private fun excerpt(text: String, word: String): String {
    val at = text.lowercase().indexOf(word).coerceAtLeast(0)
    val from = maxOf(0, at - EXCERPT)
    val to = minOf(text.length, at + word.length + EXCERPT)
    return (if (from > 0) "…" else "") + text.substring(from, to).replace('\n', ' ') + (if (to < text.length) "…" else "")
}

private fun time(at: Long) = Instant.fromEpochMilliseconds(at).toString()

private fun parseTime(text: String): Long = runCatching { Instant.parse(text).toEpochMilliseconds() }
    .recoverCatching { LocalDate.parse(text).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }
    .getOrElse { throw IllegalArgumentException("since must look like 2026-11-01 or 2026-11-01T09:00:00Z") }
