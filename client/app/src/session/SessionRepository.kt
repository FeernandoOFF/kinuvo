package kinuvo.app.session

import kinuvo.protocol.surface.SurfaceAction

import kinuvo.app.connection.*

import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.capability.supports
import kinuvo.protocol.execution.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.session.*
import kinuvo.protocol.skill.*
import kinuvo.protocol.surface.*
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val children: Map<SessionId, SubAgentState> get() = entries.filterIsInstance<SessionEntry.Notice>().mapNotNull { it.child }.associateBy { it.sessionId } + live.children
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
    val settledCount: Int = 0,
)

/** [finished] holds unread Sessions. */
data class RepositoryState(
    val sessions: Map<SessionId, SessionData> = emptyMap(),
    val lists: Map<AgentId, SessionList> = emptyMap(),
    val skills: Map<AgentId, List<SkillInfo>> = emptyMap(),
    val finished: Set<SessionId> = emptySet(),
    val settled: Set<SessionId> = emptySet(),
)

/**
 * Process-wide cache of the connected Server's Sessions, kept current from its events: session lists and statuses for
 * every Agent shown, and the transcripts of the last [MAX_CACHED] Sessions opened, each subscribed so returning to one
 * needs no reload. Work belongs to the connected Server and runs on the main dispatcher.
 */
class SessionRepository(private val notifications: Notifications, internal val connection: ConnectedServer, private val prefs: Preferences? = null) : ServerBound {
    val state: StateFlow<RepositoryState>
        field = MutableStateFlow(RepositoryState())

    private val scope get() = connection.scope
    private val client get() = connection.client
    private var attached: Job? = null
    private var settledKey: String? = null
    private var serverInbox = false
    private val inboxInfo = mutableMapOf<SessionId, SessionInfo>()
    private val pendingSettles = mutableMapOf<SessionId, UpdateSessionParams>()
    private val pendingSeen = mutableSetOf<SessionId>()
    private var settleWrites = Mutex()
    private val statuses = mutableMapOf<SessionId, SessionStatus>()
    /** Cached Sessions, least recently viewed first. */
    private val recent = mutableListOf<SessionId>()
    private val catchingUp = mutableSetOf<SessionId>()
    private val loadingLists = mutableSetOf<AgentId>()
    private val listChanges = mutableMapOf<AgentId, MutableMap<SessionId, SessionInfo>>()
    private val countRevisions = mutableMapOf<AgentId, Int>()
    private val refreshingCounts = mutableSetOf<AgentId>()
    private val replies = mutableSetOf<SessionId>()
    private val completions = mutableSetOf<String>()
    private var viewing: SessionId? = null

    override fun attach(client: KinuvoClient) {
        attached?.cancel()
        client.lastConnect?.capabilities?.let(::configureInbox)
        attached = scope.launch {
            launch { client.events(Api.Update).collect(::onUpdate) }
            launch { client.events(Api.SessionChanged).collect(::onChanged) }
            launch {
                client.foreground.collect { inboxInfo[viewing]?.let(::recordInbox) }
            }
            var migration: Job? = null
            client.state.collect {
                migration?.cancel()
                if (it is ConnectionState.Connected) {
                    val capabilities = client.lastConnect?.capabilities
                    capabilities?.let(::configureInbox)
                    resync()
                    if (serverInbox && capabilities != null)
                        migration = launch { catching { migrateSettled(client, "sessions.settled.${capabilities.serverId}") } }
                }
            }
        }
    }

    override fun detach() { clear() }

    internal fun loadSettled(serverId: String) {
        val key = "sessions.settled.$serverId"
        if (settledKey == key) return
        settledKey = key
        state.update { it.withLocalSettled(storedSettled(key)) }
    }

    private fun storedSettled(key: String): Set<SessionId> = runCatching {
        prefs?.get(key)?.let { ProtocolJson.decodeFromString(SettledSessions, it) }
    }.getOrNull().orEmpty()

    private fun saveSettled(key: String, sessions: Set<SessionId>) =
        runCatching { prefs?.set(key, sessions.takeIf { it.isNotEmpty() }?.let { ProtocolJson.encodeToString(SettledSessions, it) }) }

    private fun configureInbox(capabilities: ServerCapabilities) {
        val supported = capabilities.supports(ServerFeature.SessionInbox)
        if (supported != serverInbox) {
            serverInbox = supported
            settledKey = null
            inboxInfo.clear()
            state.update { it.copy(settled = emptySet(), finished = emptySet()) }
            if (supported) state.value.lists.values.flatMap { it.sessions }.forEach { recordInbox(it, fromPage = true) }
        }
        if (!supported) loadSettled(capabilities.serverId)
    }

    fun settle(sessionId: SessionId, settled: Boolean) {
        val client = client ?: return
        val before = state.value.settled
        val after = if (settled) before + sessionId else before - sessionId
        if (after == before) return
        if (serverInbox) {
            val info = inboxInfo[sessionId] ?: return
            val params = UpdateSessionParams(info.agentId, sessionId, settled = settled)
            pendingSettles[sessionId] = params
            recordInbox(info)
            scope.launch {
                settleWrites.withLock {
                    if (!serverInbox || pendingSettles[sessionId] !== params) return@withLock
                    val original = inboxInfo[sessionId]
                    val saved = catching { client.call(Api.UpdateSession, params) }.getOrNull()
                    if (saved != null)
                        client.lastConnect?.capabilities?.serverId?.let { runCatching { forgetMigrated("sessions.settled.$it", sessionId) } }
                    if (pendingSettles[sessionId] === params) pendingSettles -= sessionId
                    val latest = inboxInfo[sessionId]
                    (saved?.takeIf { latest === original } ?: latest)?.let { recordInbox(it) }
                }
            }
        } else {
            settledKey?.let { saveSettled(it, after) }
            state.update { it.withLocalSettled(after) }
        }
    }

    private fun forgetMigrated(key: String, id: SessionId) {
        val stored = storedSettled(key)
        if (id in stored) saveSettled(key, stored - id).getOrThrow()
    }

    private suspend fun migrateSettled(client: KinuvoClient, key: String) {
        val remaining = storedSettled(key).toMutableSet()
        if (remaining.isEmpty()) return
        for (agent in client.call(Api.Agents, Unit)) {
            var before: SessionId? = null
            do {
                val page = client.call(Api.Sessions, SessionsParams(agent.id, before = before))
                for (info in page.sessions + page.favorites) {
                    if (!remaining.remove(info.id)) continue
                    settleWrites.withLock {
                        if (info.id in storedSettled(key)) {
                            val original = inboxInfo[info.id]
                            val saved = client.call(Api.UpdateSession, UpdateSessionParams(agent.id, info.id, settled = true))
                            currentCoroutineContext().ensureActive()
                            forgetMigrated(key, info.id)
                            if (inboxInfo[info.id] === original) recordInbox(saved)
                        }
                    }
                }
                if (remaining.isEmpty()) return
                before = page.sessions.lastOrNull()?.id.takeIf { page.hasMore }
            } while (before != null)
        }
        // IDs whose Sessions have since been deleted have no owner to migrate.
        prefs?.set(key, null)
    }

    /** Shows what this Device last saw until the Server is reached; reconnecting catches the Sessions up from there. */
    internal fun restore(lists: Map<AgentId, SessionList>, sessions: Map<SessionId, CachedSession>, capabilities: ServerCapabilities?) {
        recent += sessions.keys
        state.update { it.copy(lists = lists,
            sessions = sessions.mapValues { (_, s) -> SessionData(s.agentId, s.firstSeq, s.entries, loaded = true, receivedAt = s.receivedAt) }) }
        capabilities?.let(::configureInbox)
        if (!serverInbox) state.update { it.withLocalSettled(it.settled) }
    }

    /** The cached Sessions, each with up to its last [MAX_SAVED] entries. */
    internal fun saved(): Map<SessionId, CachedSession> = state.value.sessions.filterValues { it.loaded }.mapValues { (_, d) ->
        val drop = (d.entries.size - MAX_SAVED).coerceAtLeast(0)
        CachedSession(d.agentId, d.firstSeq + drop, d.entries.drop(drop), d.receivedAt)
    }

    /** [sessionId] is on screen (null: a new Session): keeps it cached and subscribed, and clears its finished mark. */
    fun view(agentId: AgentId, sessionId: SessionId?) = scope.launch {
        val previous = viewing
        viewing = sessionId
        client?.setViewing(sessionId)
        if (serverInbox && previous != sessionId) inboxInfo[previous]?.let(::recordInbox)
        if (sessionId == null) return@launch
        state.update { it.copy(finished = it.finished - sessionId) }
        recent -= sessionId
        recent += sessionId
        if (sessionId !in state.value.sessions) {
            put(sessionId, SessionData(agentId))
            catchUp(sessionId)
        }
        while (recent.size > MAX_CACHED) evict(SessionId(recent.first().value))
        if (serverInbox && viewing == sessionId) refreshViewed(agentId, sessionId)
    }

    internal fun leaveView() {
        val previous = viewing
        viewing = null
        client?.setViewing(null)
        if (serverInbox) inboxInfo[previous]?.let(::recordInbox)
    }

    private suspend fun refreshViewed(agentId: AgentId, id: SessionId, refresh: Boolean = false) {
        val known = inboxInfo[id]
        if (known != null && !refresh) recordInbox(known)
        else {
            val info = catching { client?.call(Api.Session, SessionParams(agentId, id)) }.getOrNull() ?: return
            if (serverInbox && inboxInfo[id] === known) recordInbox(info)
        }
    }

    /** Loads the first page of [agentId]'s Sessions, or with [more] the next one. */
    fun loadSessions(agentId: AgentId, more: Boolean = false) = scope.launch {
        val client = client ?: return@launch
        val list = state.value.lists[agentId] ?: SessionList()
        if ((more && !list.hasMore) || !loadingLists.add(agentId)) return@launch
        listChanges[agentId] = mutableMapOf()
        try {
            val revision = countRevisions[agentId]
            val page = client.call(Api.Sessions, SessionsParams(agentId, before = list.nextBefore.takeIf { more }))
            currentCoroutineContext().ensureActive()
            val staleCount = revision != countRevisions[agentId]
            val changes = listChanges.remove(agentId).orEmpty()
            (page.sessions + page.favorites).filterNot { it.id in changes }.forEach { recordStatus(it, fromPage = true) }
            val loaded = if (more) state.value.lists[agentId]?.sessions.orEmpty() + page.sessions else page.favorites + page.sessions
            val sessions = (loaded.filterNot { it.id in changes } + changes.values.filter { it.inChats }).distinctBy { it.id.value }.sortedWith(SessionOrder)
            val nextBefore = page.sessions.lastOrNull()?.id.takeIf { page.hasMore }
            state.update { s ->
                val count = when {
                    !serverInbox -> sessions.localSettledCount(s.settled)
                    more || staleCount -> s.lists[agentId]?.settledCount ?: 0
                    else -> page.settledCount
                }
                s.copy(lists = s.lists + (agentId to SessionList(sessions, page.hasMore, nextBefore, count)))
            }
            if (serverInbox && !more && staleCount) refreshSettledCount(agentId)
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

    suspend fun send(agentId: AgentId, sessionId: SessionId, text: String, attachments: List<String> = emptyList(), voice: Boolean = false, submissionId: String? = null, action: SurfaceAction? = null, delivery: SendDelivery = SendDelivery.Turn, discovery: Boolean = false) =
        call {
            it.call(Api.Send, SendParams(agentId, sessionId, text, attachments, voice, submissionId, action, delivery, discovery)).also {
                currentCoroutineContext().ensureActive()
                if (!serverInbox) settle(sessionId, settled = false)
            }
        }

    suspend fun update(agentId: AgentId, sessionId: SessionId, title: String? = null, favorite: Boolean? = null): SessionInfo =
        call {
            val before = inboxInfo[sessionId]
            it.call(Api.UpdateSession, UpdateSessionParams(agentId, sessionId, title, favorite)).also { info ->
                currentCoroutineContext().ensureActive()
                if (!serverInbox || inboxInfo[sessionId] === before) onChanged(info)
            }
        }

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
                    if (!serverInbox || statuses[id] != SessionStatus.Idle) markFinished(id)
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
        val before = recordStatus(info)
        // A Routine's Sessions join the chats once they have something for the user; its finished runs are announced as Routine runs.
        listChanges[info.agentId]?.set(info.id, info)
        state.update { s ->
            val list = s.lists[info.agentId] ?: return@update s
            val others = list.sessions.filter { it.id != info.id }
            val sessions = if (!info.inChats) others
                else if (others.size < list.sessions.size) list.sessions.map { if (it.id == info.id) info else it }
                else (others + info).sortedWith(SessionOrder)
            val count = if (serverInbox) list.settledCount else sessions.localSettledCount(s.settled)
            s.copy(lists = s.lists + (info.agentId to list.copy(sessions = sessions, settledCount = count)))
        }
        if (info.routine != null) {
            if (before != SessionStatus.Waiting && info.status == SessionStatus.Waiting) notifications.post(Notice.ApprovalNeeded(info.agentId, info.id))
            if (before != null && before != SessionStatus.Idle && info.status == SessionStatus.Idle && info.inChats) {
                if (!serverInbox) markFinished(info.id)
            }
            return
        }
        if (before == info.status || before == null && info.status == SessionStatus.Idle) return
        when (info.status) {
            SessionStatus.Waiting -> notifications.post(Notice.ApprovalNeeded(info.agentId, info.id))
            SessionStatus.Idle -> {
                val external = state.value.sessions[info.id]?.live?.executionPhase != null ||
                    connection.config.state.value[info.agentId]?.details?.info?.executionKind?.let { it != ExecutionKind.Native } == true ||
                    connection.harnesses.state.value.catalog?.agents?.any { it.id == info.agentId } == true
                if (external) return
                notifications.post(Notice.RunFinished(info.agentId, info.id))
                if (!serverInbox) markFinished(info.id)
                // A run may have created or edited Skills.
                if (info.agentId in state.value.skills) loadSkills(info.agentId)
            }
            SessionStatus.Running -> Unit
        }
    }

    private fun recordStatus(info: SessionInfo, fromPage: Boolean = false): SessionStatus? {
        val before = statuses.put(info.id, info.status)
        if (serverInbox) recordInbox(info, fromPage)
        else if (before != info.status && info.status != SessionStatus.Idle) settle(info.id, settled = false)
        return before
    }

    private fun recordInbox(info: SessionInfo, fromPage: Boolean = false) {
        val id = info.id
        val previous = inboxInfo.put(id, info)
        val agent = info.agentId
        val viewingNow = viewing == id && client?.foreground?.value == true
        val settled = pendingSettles[id]?.settled ?: info.settled
        state.update { s ->
            s.copy(
                settled = if (settled) s.settled + id else s.settled - id,
                finished = if (info.unread && !viewingNow) s.finished + id else s.finished - id,
            )
        }
        if (!fromPage && (agent in state.value.lists || agent in loadingLists) &&
            (previous == null || previous.settled != info.settled || previous.favorite != info.favorite || previous.inChats != info.inChats)) {
            countRevisions[agent] = (countRevisions[agent] ?: 0) + 1
            refreshSettledCount(agent)
        }
        if (info.unread && viewingNow) markSeen(info)
    }

    private fun refreshSettledCount(agentId: AgentId) = scope.launch {
        val client = client ?: return@launch
        if (!refreshingCounts.add(agentId)) return@launch
        try {
            var revision: Int?
            var page: SessionPage
            do {
                revision = countRevisions[agentId]
                page = catching { client.call(Api.Sessions, SessionsParams(agentId, limit = 1)) }.getOrNull() ?: return@launch
                currentCoroutineContext().ensureActive()
            } while (revision != countRevisions[agentId])
            if (serverInbox) state.update { s ->
                val list = s.lists[agentId] ?: return@update s
                s.copy(lists = s.lists + (agentId to list.copy(settledCount = page.settledCount)))
            }
        } finally {
            if (connection.isCurrent()) refreshingCounts -= agentId
        }
    }

    private fun markSeen(info: SessionInfo) {
        val client = client ?: return
        if (client.state.value !is ConnectionState.Connected || !pendingSeen.add(info.id)) return
        scope.launch {
            val saved = catching { client.call(Api.UpdateSession, UpdateSessionParams(info.agentId, info.id, seen = true)) }.getOrNull()
            pendingSeen -= info.id
            if (serverInbox && saved != null) recordInbox(inboxInfo[info.id]?.takeUnless { it === info } ?: saved)
        }
    }

    private fun markFinished(sessionId: SessionId) {
        if (sessionId == viewing && (!serverInbox || client?.foreground?.value == true)) return
        if (!serverInbox) settle(sessionId, settled = false)
        state.update { it.copy(finished = it.finished + sessionId) }
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
        if (serverInbox) viewing?.let { id -> state.value.sessions[id]?.let { scope.launch { refreshViewed(it.agentId, id, refresh = true) } } }
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
        countRevisions.clear()
        refreshingCounts.clear()
        settledKey = null
        serverInbox = false
        inboxInfo.clear()
        pendingSettles.clear()
        pendingSeen.clear()
        settleWrites = Mutex()
        state.value = RepositoryState()
    }

    /** [received]: [data] holds what just arrived live, so its new replies get this Device's receipt time; history keeps none. */
    private fun put(sessionId: SessionId, data: SessionData, received: Boolean = false) =
        state.update { it.copy(sessions = it.sessions + (sessionId to if (received) data.withReceiptTimes(it.sessions[sessionId]) else data)) }

    private companion object {
        const val MAX_CACHED = 8
        const val MAX_SAVED = 100
        val SettledSessions = SetSerializer(SessionId.serializer())
    }
}

private fun RepositoryState.withLocalSettled(settled: Set<SessionId>) = copy(settled = settled, lists = lists.mapValues { (_, list) ->
    list.copy(settledCount = list.sessions.localSettledCount(settled))
})

private fun List<SessionInfo>.localSettledCount(settled: Set<SessionId>) = count { it.id in settled && !it.favorite && it.inChats }

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
            is SessionEntry.Notice -> if (entry.child == null) items += ChatItem.Notice(key("notice-${entry.id}", seq), entry.code, entry.message, SessionData(agentId, entries = entry.draft).items)
            is SessionEntry.Started, is SessionEntry.Settings, is SessionEntry.Details, is SessionEntry.Context, is SessionEntry.Outcome -> Unit
        }
    }
    live.pending.forEach { request ->
        if (request.id in live.interactionChildren) return@forEach
        when (request) {
            is PendingInteraction.Approval -> {
                val call = items.indexOfLast { it is ChatItem.ToolCall && it.callId == request.toolCallId && it.result == null }
                if (call >= 0) items[call] = (items[call] as ChatItem.ToolCall).copy(approval = request)
                // An MCP App's own tool call asks outside a run, with no call in the list yet; a Workflow's Approval is no tool call at all.
                else if (request.toolCallName != WORKFLOW_APPROVAL) items += ChatItem.ToolCall(key("approval-${request.id}", -4), request.toolCallId, request.toolCallName, request.args, approval = request, identity = request.identity)
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

/** The tool name a Workflow's Approval step asks under; it stands for the step, not for a tool. */
const val WORKFLOW_APPROVAL = "workflow_approval"
