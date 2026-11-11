package kinuvo.app.session

import kinuvo.protocol.surface.SurfaceAction

import kinuvo.app.connection.*

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.session.*
import kinuvo.protocol.skill.*
import kinuvo.protocol.surface.*
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * One Session as held on this Device: a window of its entries starting at [firstSeq], the live turn and run notes.
 * [loaded] turns true once the first catch-up arrived.
 */
data class SessionData(
    val agentId: AgentId,
    val firstSeq: Int = 0,
    val entries: List<SessionEntry> = emptyList(),
    val live: LiveState = LiveState(),
    val notes: List<String> = emptyList(),
    val loaded: Boolean = false,
    val loadingOlder: Boolean = false,
    val receivedAt: Map<String, Long> = emptyMap(),
) {
    val seen get() = firstSeq + entries.size
    val hasOlder get() = firstSeq > 0
    val items: List<ChatItem> by lazy { chatItems() }
}

/** The loaded pages of an Agent's Sessions, newest first. */
@Serializable
data class SessionList(
    val sessions: List<SessionInfo> = emptyList(),
    val hasMore: Boolean = false,
    val nextBefore: SessionId? = null,
)

/** [finished] holds Sessions whose run ended while they weren't on screen. */
data class RepositoryState(
    val sessions: Map<SessionId, SessionData> = emptyMap(),
    val lists: Map<AgentId, SessionList> = emptyMap(),
    val skills: Map<AgentId, List<SkillInfo>> = emptyMap(),
    val finished: Set<SessionId> = emptySet(),
)

/**
 * Process-wide cache of the connected Server's Sessions, kept current from its events: session lists and statuses for
 * every Agent shown, and the transcripts of the last [MAX_CACHED] Sessions opened, each subscribed so returning to one
 * needs no reload. Work belongs to the connected Server and runs on the main dispatcher.
 */
class SessionRepository(private val notifications: Notifications, internal val connection: ConnectedServer) {
    val state: StateFlow<RepositoryState>
        field = MutableStateFlow(RepositoryState())

    private val scope get() = connection.scope
    private val client get() = connection.client
    private var attached: Job? = null
    private val statuses = mutableMapOf<SessionId, SessionStatus>()
    /** Cached Sessions, least recently viewed first. */
    private val recent = mutableListOf<SessionId>()
    private val catchingUp = mutableSetOf<SessionId>()
    private val loadingLists = mutableSetOf<AgentId>()
    private val listChanges = mutableMapOf<AgentId, MutableMap<SessionId, SessionInfo>>()
    private val replies = mutableSetOf<SessionId>()
    private val completions = mutableSetOf<String>()
    private var viewing: SessionId? = null

    internal fun attach(client: KinuvoClient) {
        attached?.cancel()
        attached = scope.launch {
            launch { client.events(Api.Update).collect(::onUpdate) }
            launch { client.events(Api.SessionChanged).collect(::onChanged) }
            client.state.collect { if (it is ConnectionState.Connected) resync() }
        }
    }

    internal fun detach() { clear() }

    /** Shows what this Device last saw until the Server is reached; reconnecting catches the Sessions up from there. */
    internal fun restore(lists: Map<AgentId, SessionList>, sessions: Map<SessionId, CachedSession>) {
        recent += sessions.keys
        state.value = RepositoryState(lists = lists,
            sessions = sessions.mapValues { (_, s) -> SessionData(s.agentId, s.firstSeq, s.entries, loaded = true, receivedAt = s.receivedAt) })
    }

    /** The cached Sessions, each with up to its last [MAX_SAVED] entries. */
    internal fun saved(): Map<SessionId, CachedSession> = state.value.sessions.filterValues { it.loaded }.mapValues { (_, d) ->
        val drop = (d.entries.size - MAX_SAVED).coerceAtLeast(0)
        CachedSession(d.agentId, d.firstSeq + drop, d.entries.drop(drop), d.receivedAt)
    }

    /** [sessionId] is on screen (null: a new Session): keeps it cached and subscribed, and clears its finished mark. */
    fun view(agentId: AgentId, sessionId: SessionId?) = scope.launch {
        viewing = sessionId
        if (sessionId == null) return@launch
        state.update { it.copy(finished = it.finished - sessionId) }
        recent -= sessionId
        recent += sessionId
        if (sessionId !in state.value.sessions) {
            put(sessionId, SessionData(agentId))
            catchUp(sessionId)
        }
        while (recent.size > MAX_CACHED) evict(SessionId(recent.first().value))
    }

    /** Loads the first page of [agentId]'s Sessions, or with [more] the next one. */
    fun loadSessions(agentId: AgentId, more: Boolean = false) = scope.launch {
        val client = client ?: return@launch
        val list = state.value.lists[agentId] ?: SessionList()
        if ((more && !list.hasMore) || !loadingLists.add(agentId)) return@launch
        listChanges[agentId] = mutableMapOf()
        try {
            val page = client.call(Api.Sessions, SessionsParams(agentId, before = list.nextBefore.takeIf { more }))
            currentCoroutineContext().ensureActive()
            val changes = listChanges.remove(agentId).orEmpty()
            page.sessions.filterNot { it.id in changes }.forEach { statuses[it.id] = it.status }
            val loaded = if (more) state.value.lists[agentId]?.sessions.orEmpty() + page.sessions else page.favorites + page.sessions
            val sessions = (loaded.filterNot { it.id in changes } + changes.values.filter { it.inChats }).distinctBy { it.id.value }.sortedWith(SessionOrder)
            val nextBefore = page.sessions.lastOrNull()?.id.takeIf { page.hasMore }
            state.update { it.copy(lists = it.lists + (agentId to SessionList(sessions, page.hasMore, nextBefore?.value?.let(::SessionId)))) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            if (connection.isCurrent()) {
                listChanges -= agentId
                loadingLists -= agentId
            }
        }
    }

    fun loadSkills(agentId: AgentId) = scope.launch {
        val skills = catching { client?.call(Api.Skills, AgentRef(agentId)) }.getOrNull() ?: return@launch
        state.update { it.copy(skills = it.skills + (agentId to skills)) }
    }

    /** Prepends the page of entries before the cached window. */
    fun loadOlder(sessionId: SessionId) = scope.launch {
        val client = client ?: return@launch
        val data = state.value.sessions[sessionId]?.takeIf { it.hasOlder && !it.loadingOlder } ?: return@launch
        put(sessionId, data.copy(loadingOlder = true))
        val page = catching { client.call(Api.History, HistoryParams(data.agentId, sessionId, before = data.firstSeq)) }.getOrNull()
        val now = state.value.sessions[sessionId] ?: return@launch
        put(sessionId, if (page.isNullOrEmpty() || now.firstSeq != data.firstSeq) now.copy(loadingOlder = false)
            else now.copy(firstSeq = page.first().seq, entries = page.map { it.entry } + now.entries, loadingOlder = false))
    }

    suspend fun createSession(agentId: AgentId): SessionInfo = call { it.call(Api.CreateSession, AgentRef(agentId)) }

    suspend fun send(agentId: AgentId, sessionId: SessionId, text: String, attachments: List<String> = emptyList(), voice: Boolean = false, submissionId: String? = null, action: SurfaceAction? = null) =
        call { it.call(Api.Send, SendParams(agentId, sessionId, text, attachments, voice, submissionId, action)) }

    suspend fun update(agentId: AgentId, sessionId: SessionId, title: String? = null, favorite: Boolean? = null) =
        call { it.call(Api.UpdateSession, UpdateSessionParams(agentId, sessionId, title, favorite)) }.also(::onChanged)

    suspend fun resume(sessionId: SessionId) = call { it.call(Api.ResumeExecution, ExecutionRef(sessionId.value)) }

    suspend fun close(sessionId: SessionId) = call { it.call(Api.CloseExecution, ExecutionRef(sessionId.value)) }

    /** Answers the pending request [requestId]; false when another Device answered first or it is gone. */
    suspend fun respond(sessionId: SessionId, requestId: InteractionId, answer: InteractionAnswer): Boolean =
        call { it.call(Api.Respond, RespondParams(sessionId, requestId, answer)) }

    suspend fun stop(sessionId: SessionId) = call { it.call(Api.Stop, StopParams(sessionId)) }

    private suspend fun <T> call(block: suspend (KinuvoClient) -> T): T =
        connection.call(block)

    private fun onUpdate(update: SessionUpdate) {
        val id = update.sessionId
        val data = state.value.sessions[id] ?: return
        val change = update.change
        val event = (change as? SessionChange.Event)?.event
        if (data.live.executionPhase != null) {
            when {
                event is AgentEvent.RunStarted -> replies -= id
                change is SessionChange.Entry && change.entry is SessionEntry.AssistantMessage -> replies += id
                change is SessionChange.Entry && change.entry is SessionEntry.Notice -> replies -= id
                event is AgentEvent.RunError || event is AgentEvent.ExecutionPhaseChanged && event.phase == ExecutionPhase.Lost -> replies -= id
                event is AgentEvent.RunFinished && replies.remove(id) && data.live.executionPhase != ExecutionPhase.Lost && completions.add("$id:${event.runId}") -> {
                    notifications.post(Notice.RunFinished(update.agentId, id))
                    if (id.value != viewing?.value) state.update { it.copy(finished = it.finished + id) }
                }
            }
        }
        when (change) {
            is SessionChange.Window -> put(id, data.copy(firstSeq = change.firstSeq, entries = change.entries, live = change.state, loaded = true))
            is SessionChange.Reset -> put(id, data.copy(firstSeq = change.firstSeq, entries = emptyList()))
            is SessionChange.Entry -> when {
                change.seq < data.seen -> Unit
                change.seq > data.seen -> scope.launch { catchUp(id) }
                else -> put(id, data.copy(entries = data.entries + change.entry, live = data.live.on(change.entry)), received = true)
            }
            is SessionChange.Event -> put(id, data.copy(live = data.live.on(change.event), notes = data.notes + listOfNotNull(note(change.event))), received = true)
            is SessionChange.Live -> put(id, data.copy(live = change.state, loaded = true))
        }
    }

    private fun onChanged(info: SessionInfo) {
        // A Routine's Sessions join the chats once they have something for the user; its finished runs are announced as Routine runs.
        listChanges[info.agentId]?.set(info.id, info)
        state.update { s ->
            val list = s.lists[info.agentId] ?: return@update s
            val others = list.sessions.filter { it.id != info.id }
            val sessions = if (!info.inChats) others
                else if (others.size < list.sessions.size) list.sessions.map { if (it.id == info.id) info else it }
                else (others + info).sortedWith(SessionOrder)
            s.copy(lists = s.lists + (info.agentId to list.copy(sessions = sessions)))
        }
        if (info.routine != null) {
            val before = statuses.put(info.id, info.status)
            if (before != SessionStatus.Waiting && info.status == SessionStatus.Waiting) notifications.post(Notice.ApprovalNeeded(info.agentId, info.id))
            if (before != null && before != SessionStatus.Idle && info.status == SessionStatus.Idle && info.inChats && info.id.value != viewing?.value) {
                state.update { it.copy(finished = it.finished + info.id) }
            }
            return
        }
        val before = statuses.put(info.id, info.status)
        if (before == info.status || before == null && info.status == SessionStatus.Idle) return
        when (info.status) {
            SessionStatus.Waiting -> notifications.post(Notice.ApprovalNeeded(info.agentId, info.id))
            SessionStatus.Idle -> {
                val external = state.value.sessions[info.id]?.live?.executionPhase != null ||
                    connection.config.state.value[info.agentId]?.details?.info?.executionKind?.let { it != ExecutionKind.Native } == true ||
                    connection.harnesses.state.value.catalog?.agents?.any { it.id == info.agentId } == true
                if (external) return
                notifications.post(Notice.RunFinished(info.agentId, info.id))
                if (info.id.value != viewing?.value) state.update { it.copy(finished = it.finished + info.id) }
                // A run may have created or edited Skills.
                if (info.agentId in state.value.skills) loadSkills(info.agentId)
            }
            SessionStatus.Running -> Unit
        }
    }

    /** Subscribes to [sessionId] from what's held, or a fresh window when nothing is. */
    private suspend fun catchUp(sessionId: SessionId) {
        val client = client ?: return
        val data = state.value.sessions[sessionId] ?: return
        if (!catchingUp.add(sessionId)) return
        try {
            client.call(Api.CatchUp, CatchUpParams(data.agentId, sessionId, after = if (data.loaded) data.seen else null))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            if (connection.isCurrent()) catchingUp -= sessionId
        }
    }

    /** After (re)connecting: subscriptions start over, so catch up every cached Session and refresh what's shown. */
    private fun resync() {
        state.value.sessions.keys.forEach { scope.launch { catchUp(it) } }
        state.value.lists.keys.forEach { loadSessions(it) }
        state.value.skills.keys.forEach { loadSkills(it) }
    }

    private fun evict(sessionId: SessionId) {
        recent -= sessionId
        state.update { it.copy(sessions = it.sessions - sessionId) }
        val client = client ?: return
        scope.launch { catching { client.call(Api.Unsubscribe, UnsubscribeParams(sessionId)) } }
    }

    private fun clear() {
        attached?.cancel()
        viewing = null
        statuses.clear()
        replies.clear()
        completions.clear()
        recent.clear()
        catchingUp.clear()
        loadingLists.clear()
        listChanges.clear()
        state.value = RepositoryState()
    }

    /** [received]: [data] holds what just arrived live, so its new replies get this Device's receipt time; history keeps none. */
    private fun put(sessionId: SessionId, data: SessionData, received: Boolean = false) =
        state.update { it.copy(sessions = it.sessions + (sessionId to if (received) data.withReceiptTimes(it.sessions[sessionId]) else data)) }

    private companion object {
        const val MAX_CACHED = 8
        const val MAX_SAVED = 100
    }
}

private fun note(event: AgentEvent): String? = when (event) {
    is AgentEvent.RunError -> event.message
    is AgentEvent.Warning -> "⚠ ${event.message}"
    else -> null
}

/** Transcript items with stable keys: a message keeps its key from live text to saved entry. */
private fun SessionData.chatItems(): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    val keys = mutableSetOf<String>()
    fun key(preferred: String?, seq: Int) = preferred?.takeIf { keys.add(it) } ?: "entry-$seq".also { keys += it }
    val reviews = entries.filterIsInstance<SessionEntry.ReflectionReview>().associate { it.proposalId to it.approved }
    val revised = entries.filterIsInstance<SessionEntry.ReflectionReview>().filter { it.approved && it.note != null }.map { it.proposalId }.toSet()
    val saved = entries.mapNotNull { it.identity() }.toSet()
    val drafts = entries.filterIsInstance<SessionEntry.Notice>().flatMap { it.draft }.mapNotNull { it.identity() }.toSet()
    val projected = entries + live.entries.filter { it.identity() !in saved && it.identity() !in drafts }
    projected.forEachIndexed { i, entry ->
        val seq = firstSeq + i
        when (entry) {
            is SessionEntry.UserMessage -> items += ChatItem.User(key(entry.submissionId?.let { "user-$it" }, seq), entry.text, entry.routine?.name, entry.attachments, parseTaskPrompt(entry.text)?.second, voice = entry.voice, action = entry.action)
            is SessionEntry.VoiceCall -> items += ChatItem.VoiceCall(entry.id, entry.turns, entry.startedAt, entry.durationMillis)
            is SessionEntry.AssistantMessage -> items += ChatItem.Assistant(key(entry.messageId?.let { "text-$it" }, seq), entry.text, at = entry.writtenAt)
            is SessionEntry.Reasoning -> items += ChatItem.Reasoning(key((entry.messageId ?: entry.id)?.let { "reasoning-$it" }, seq), entry.text)
            is SessionEntry.ToolCall -> items += ChatItem.ToolCall(key("tool-${entry.id}", seq), entry.id, entry.name, entry.args, identity = entry.identity)
            is SessionEntry.ToolResult -> {
                val call = items.indexOfLast { it is ChatItem.ToolCall && it.callId == entry.id && it.result == null }
                if (call >= 0) items[call] = (items[call] as ChatItem.ToolCall).copy(result = entry.output, isError = entry.isError, attachments = entry.attachments, app = entry.app, appResult = entry.appResult)
                else items += ChatItem.ToolCall(key("tool-${entry.id}", seq), entry.id, entry.name, "", entry.output, entry.isError, identity = entry.identity, attachments = entry.attachments, app = entry.app, appResult = entry.appResult)
            }
            is SessionEntry.Measurement -> items += ChatItem.Measurement(key("metrics-${entry.metrics.id}", seq), entry.metrics)
            is SessionEntry.Reflection -> items += ChatItem.Reflection(key("reflection-${entry.id}", seq), entry.result, reviews, revised)
            is SessionEntry.Compaction -> items += ChatItem.Compaction(key(null, seq), entry.summary)
            is SessionEntry.ToolReport -> items += ChatItem.ToolReport(key(null, seq), entry.tool, entry.callId, entry.content)
            is SessionEntry.Activation -> items += ChatItem.Activation(key(null, seq), entry.name, entry.content)
            is SessionEntry.ReflectionReview -> entry.result?.takeIf { it.proposals.isNotEmpty() }?.let {
                items += ChatItem.Reflection(key("reflection-review-${entry.proposalId}", seq), it, reviews, revised)
            }
            is SessionEntry.Notice -> items += ChatItem.Notice(key("notice-${entry.id}", seq), entry.code, entry.message, SessionData(agentId, entries = entry.draft).items)
            is SessionEntry.Started, is SessionEntry.Settings, is SessionEntry.Details, is SessionEntry.Context, is SessionEntry.Outcome -> Unit
        }
    }
    live.pending.forEach { request ->
        when (request) {
            is PendingInteraction.Approval -> {
                val call = items.indexOfLast { it is ChatItem.ToolCall && it.callId == request.toolCallId && it.result == null }
                if (call >= 0) items[call] = (items[call] as ChatItem.ToolCall).copy(approval = request)
                // An MCP App's own tool call asks outside a run, with no call in the list yet.
                else items += ChatItem.ToolCall(key("approval-${request.id}", -4), request.toolCallId, request.toolCallName, request.args, approval = request, identity = request.identity)
            }
            is PendingInteraction.Questions -> {
                val call = items.indexOfLast { it is ChatItem.ToolCall && it.callId == request.toolCallId && it.result == null }
                if (call >= 0) items[call] = (items[call] as ChatItem.ToolCall).copy(questions = request)
            }
            else -> Unit
        }
    }
    if (live.reasoning.isNotEmpty() && "reasoning-${live.messageId}" !in keys) items += ChatItem.Reasoning(key("reasoning-${live.messageId}", -1), live.reasoning)
    if (live.text.isNotEmpty() && "text-${live.messageId}" !in keys) items += ChatItem.Assistant(key("text-${live.messageId}", -2), live.text)
    live.reflectionId?.takeIf { "reflection-$it" !in keys }?.let { items += ChatItem.Reflection(key("reflection-$it", -3)) }
    notes.forEachIndexed { i, note -> items += ChatItem.Error("note-$i", note) }
    return items.map { if (it is ChatItem.Assistant) it.copy(at = it.at ?: receivedAt[it.id]) else it }
}

private fun SessionData.withReceiptTimes(previous: SessionData?): SessionData {
    val before = previous?.items.orEmpty().mapTo(mutableSetOf()) { it.id }
    val fresh = items.filter { it is ChatItem.Assistant && it.at == null && it.id !in before && it.id !in receivedAt }
    if (fresh.isEmpty()) return this
    val now = Clock.System.now().toEpochMilliseconds()
    return copy(receivedAt = receivedAt + fresh.associate { it.id to now })
}
