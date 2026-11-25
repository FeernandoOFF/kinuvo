package kinuvo.server

import kinuvo.server.store.Store
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.LiveState
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.execution.ExecutionPhase
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.session.SessionChange
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.withWrittenAt
import kotlin.time.Clock
import kinuvo.protocol.session.SessionParent
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionOrder
import kinuvo.protocol.session.SessionPage
import kinuvo.protocol.session.SessionStatus
import kinuvo.protocol.session.SessionUpdate
import kinuvo.protocol.session.SubAgentState
import kinuvo.protocol.session.isLive
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("kinuvo.sessions")

/**
 * Every Session as one ordered stream of [SessionUpdate]s: its entries (seq = position) plus the active run's [LiveState],
 * delivered to the clients subscribed to it. Appends, live events and subscriptions share one lock, so a subscriber never
 * misses or repeats an update. Status changes go to every [onChange] listener. The lock isn't reentrant: code holding
 * it only calls the underlying [store] and the private helpers.
 *
 * For now entries stay in the Store's JSONL and live state only in memory. With Exposed, migrate to one events
 * table keyed by (session, seq) that holds both, and serve catch-ups and history with range queries.
 */
@OptIn(ExperimentalAtomicApi::class)
class SessionLog(private val store: Store, private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : Store by store {
    /** Receives one client's updates; returns false when it can't keep up, which unsubscribes it everywhere. */
    fun interface Subscriber {
        fun offer(update: SessionUpdate): Boolean
        /** False while the client is in the background, so it doesn't count as watching. */
        val watching: Boolean get() = true
    }

    private val lock = Mutex()
    private val counts = HashMap<SessionId, Int>()
    private val live = HashMap<SessionId, LiveState>()
    private val completions = HashMap<SessionId, AgentEvent>()
    private enum class RunReply { None, Chat, Routine, Report }
    private val runReplies = HashMap<SessionId, RunReply>()
    suspend fun completion(sessionId: SessionId): AgentEvent? = lock.withLock { completions[sessionId] }
    private val subscribers = HashMap<SessionId, MutableSet<Subscriber>>()
    private val viewing = HashMap<Subscriber, SessionId>()
    private val changeListeners = AtomicReference(emptySet<(SessionInfo) -> Unit>())
    private val eventListeners = AtomicReference(emptySet<(AgentId, SessionId, AgentEvent) -> Unit>())

    /** [listener] must not block: it's called while holding the lock. */
    fun onChange(listener: (SessionInfo) -> Unit) {
        changeListeners.update { it + listener }
    }

    /** Sees every run event; [listener] must not block: it's called while holding the lock. */
    fun onEvent(listener: (AgentId, SessionId, AgentEvent) -> Unit) {
        eventListeners.update { it + listener }
    }

    /** Whether a client in the foreground is subscribed to [sessionId]. */
    suspend fun watched(sessionId: SessionId): Boolean = lock.withLock { subscribers[sessionId].orEmpty().any { it.watching } }

    suspend fun setViewing(client: Subscriber, sessionId: SessionId?) = lock.withLock {
        if (sessionId == null) viewing.remove(client) else viewing[client] = sessionId
        Unit
    }

    suspend fun pending(sessionId: SessionId): List<PendingInteraction> = lock.withLock { live[sessionId]?.pending.orEmpty() }

    suspend fun status(sessionId: SessionId): SessionStatus = lock.withLock { statusOf(sessionId) }

    var executionMetadata: suspend (SessionInfo) -> SessionInfo = { it }

    override suspend fun session(agentId: AgentId, sessionId: SessionId): SessionInfo = executionMetadata(store.session(agentId, sessionId).copy(status = status(sessionId)))

    /** [before] is the last Session ID of the previous page; [routineId] picks that Routine's Sessions instead of the chats. */
    suspend fun page(agentId: AgentId, before: String?, limit: Int, routineId: String? = null): SessionPage {
        val sessions = lock.withLock { store.sessions(agentId).map { executionMetadata(it).copy(status = statusOf(it.id)) } }
            .sortedWith(SessionOrder)
        val all = sessions.filter { if (routineId == null) it.inChats else !it.hidden && it.routine?.id == routineId }
        val from = before?.let { id -> all.indexOfFirst { it.id.value == id } + 1 } ?: 0
        val page = all.drop(from).take(limit)
        val firstChats = before == null && routineId == null
        return SessionPage(page, from + page.size < all.size, if (firstChats) all.filter { it.favorite } else emptyList(),
            if (before == null) sessions.count { it.inChats && it.settled && !it.favorite } else 0)
    }

    override suspend fun createSession(agentId: AgentId, instructions: String, routine: RoutineRef?): SessionInfo =
        store.createSession(agentId, instructions, routine).also { info -> lock.withLock { changed(info) } }

    suspend fun createChild(agentId: AgentId, parent: SessionParent, settings: SessionSettings? = null): SessionInfo = lock.withLock {
        val info = store.createSession(agentId, "")
        if (settings != null) store.append(agentId, info.id, SessionEntry.Settings(settings))
        store.append(agentId, info.id, SessionEntry.Details(parent = parent, hidden = settings != null))
        store.session(agentId, info.id).also { changed(it) }
    }

    var onDelete: suspend (AgentId, SessionId) -> Unit = { _, _ -> }
    private val deleting = mutableSetOf<SessionId>()

    override suspend fun deleteSession(agentId: AgentId, sessionId: SessionId) = delete(agentId, sessionId)
    internal suspend fun deleteChildIfPresent(agentId: AgentId, sessionId: SessionId) = delete(agentId, sessionId, ifPresent = true)

    private suspend fun delete(agent: AgentId, id: SessionId, ifPresent: Boolean = false) {
        lock.withLock {
            if (ifPresent && store.sessions(agent).none { it.id == id }) return
            store.entryCount(agent, id)
            if (!deleting.add(id)) return
        }
        try {
            onDelete(agent, id)
            val children = lock.withLock {
                val linkedOwners = store.entries(agent, id).filterIsInstance<SessionEntry.Notice>().mapNotNull { it.child?.agentId }
                val owners = (linkedOwners + store.agents().map { it.id }).distinct()
                owners.flatMap { store.sessions(it) }.filter { it.parent?.sessionId == id }
            }
            children.forEach { deleteChildIfPresent(it.agentId, it.id) }
            lock.withLock {
                store.deleteSession(agent, id)
                counts -= id
                live -= id
                completions -= id
                runReplies -= id
                subscribers -= id
            }
        } finally {
            withContext(NonCancellable) { lock.withLock { deleting -= id } }
        }
    }

    /** Drops entries from [size] on and sends subscribers a fresh window, since the entries they hold changed. */
    override suspend fun truncate(agentId: AgentId, sessionId: SessionId, size: Int) = lock.withLock {
        truncate(agentId, sessionId, size, false)
    }

    suspend fun discardSilentRun(agentId: AgentId, sessionId: SessionId, size: Int) = lock.withLock {
        truncate(agentId, sessionId, size, true)
    }

    private suspend fun truncate(agentId: AgentId, sessionId: SessionId, size: Int, keepSettings: Boolean) {
        val dropped = store.entries(agentId, sessionId).drop(size)
        val kept = listOfNotNull(
            dropped.filterIsInstance<SessionEntry.Settings>().lastOrNull()?.takeIf { keepSettings },
            dropped.filterIsInstance<SessionEntry.Details>().lastOrNull(),
        )
        store.truncate(agentId, sessionId, size)
        kept.forEach { store.append(agentId, sessionId, it) }
        val entries = store.entries(agentId, sessionId)
        counts[sessionId] = entries.size
        subscribers[sessionId]?.toList()?.forEach { send(agentId, sessionId, entries, null, RESYNC_LIMIT, it) }
        changed(store.session(agentId, sessionId).copy(status = statusOf(sessionId)))
    }

    suspend fun updateDetails(
        agentId: AgentId, sessionId: SessionId, title: String? = null, favorite: Boolean? = null,
        settled: Boolean? = null, seen: Boolean? = null,
    ): SessionInfo = updateDetails(agentId, sessionId) { previous ->
        previous.copy(
            title = if (title == null) previous.title else title.trim().take(MAX_SESSION_TITLE).ifEmpty { null },
            favorite = favorite ?: previous.favorite,
            settled = settled ?: previous.settled,
            unread = previous.unread && seen != true,
        )
    }

    internal suspend fun updateDetails(agentId: AgentId, sessionId: SessionId, edit: (SessionEntry.Details) -> SessionEntry.Details): SessionInfo = lock.withLock {
        val previous = store.sessionDetails(agentId, sessionId)
        val next = edit(previous)
        if (next != previous) saveDetails(agentId, sessionId, next)
        store.session(agentId, sessionId).copy(status = statusOf(sessionId)).also { if (next != previous) changed(it) }
    }

    private suspend fun saveDetails(agentId: AgentId, sessionId: SessionId, details: SessionEntry.Details) {
        val seq = count(agentId, sessionId)
        val next = details.copy(writtenAt = clock())
        store.append(agentId, sessionId, next)
        saved(agentId, sessionId, seq, next)
    }

    override suspend fun append(agentId: AgentId, sessionId: SessionId, entry: SessionEntry) = lock.withLock {
        val seq = count(agentId, sessionId)
        val stamped = entry.withWrittenAt(entry.writtenAt ?: clock())
        store.append(agentId, sessionId, stamped)
        saved(agentId, sessionId, seq, stamped)
        metadata(agentId, sessionId, listOf(stamped))
    }

    override suspend fun appendAll(agentId: AgentId, sessionId: SessionId, entries: List<SessionEntry>): List<SessionEntry> = lock.withLock {
        val seq = count(agentId, sessionId)
        val added = store.appendAll(agentId, sessionId, entries.map { it.withWrittenAt(it.writtenAt ?: clock()) })
        added.forEachIndexed { index, entry -> saved(agentId, sessionId, seq + index, entry) }
        metadata(agentId, sessionId, added)
        added
    }

    private fun saved(agentId: AgentId, sessionId: SessionId, seq: Int, entry: SessionEntry) {
        counts[sessionId] = seq + 1
        runReplies[sessionId]?.let { reply ->
            runReplies[sessionId] = when {
                entry is SessionEntry.UserMessage && entry.routine != null -> RunReply.Routine
                entry is SessionEntry.Outcome -> if (entry.report) RunReply.Report else RunReply.Routine
                entry is SessionEntry.AssistantMessage && entry.text.isNotBlank() && reply == RunReply.None -> RunReply.Chat
                else -> reply
            }
        }
        val visible = entry.forClient()
        live[sessionId]?.let {
            val state = it.on(visible)
            if (state.queued.isNotEmpty() || state.running || state.reflectionId != null || state.executionPhase != null || state.entries.isNotEmpty() || state.pending.isNotEmpty() || state.children.isNotEmpty())
                live[sessionId] = state else live.remove(sessionId)
        }
        deliver(SessionUpdate(agentId, sessionId, SessionChange.Entry(seq, visible)))
    }

    /** Persists [event]'s text as a [SessionEntry.Notice] so it gets a real seq, instead of only ever reaching a client that was subscribed at the instant it fired. */
    private suspend fun recordNotice(agentId: AgentId, sessionId: SessionId, code: String, message: String) {
        val seq = count(agentId, sessionId)
        val entry = SessionEntry.Notice(Uuid.random().toString(), code, message, writtenAt = clock())
        store.append(agentId, sessionId, entry)
        saved(agentId, sessionId, seq, entry)
    }

    private suspend fun metadata(agentId: AgentId, sessionId: SessionId, entries: List<SessionEntry>) {
        if (entries.any { it is SessionEntry.UserMessage || it is SessionEntry.Settings || it is SessionEntry.Details })
            changed(store.session(agentId, sessionId).copy(status = statusOf(sessionId)))
    }

    suspend fun publish(agentId: AgentId, sessionId: SessionId, update: AgentEvent) = lock.withLock {
        val event = when (update) {
            is AgentEvent.SubAgentChanged -> update.copy(child = update.child.forClient())
            is AgentEvent.ExecutionEntryChanged -> update.copy(entry = update.entry.forClient())
            else -> update
        }
        store.entryCount(agentId, sessionId)
        when(event) {
            is AgentEvent.RunStarted -> completions.remove(sessionId)
            is AgentEvent.RunFinished, is AgentEvent.RunError -> completions[sessionId] = event
            is AgentEvent.ExecutionPhaseChanged -> if (event.phase == ExecutionPhase.Running && live[sessionId]?.executionPhase == ExecutionPhase.Idle) completions.remove(sessionId) else Unit
            else -> Unit
        }
        // A plain live event would only reach a subscriber watching at this exact moment, and render wherever the
        // client happens to append it; as an entry it gets a real seq, so it survives a reconnect and sits where it belongs.
        when (event) {
            is AgentEvent.RunError -> recordNotice(agentId, sessionId, "run_error", event.message)
            is AgentEvent.Warning -> recordNotice(agentId, sessionId, "warning", event.message)
            else -> Unit
        }
        val previous = live[sessionId] ?: LiveState()
        val state = previous.on(event)
        val started = event is AgentEvent.RunStarted || event is AgentEvent.ExecutionPhaseChanged &&
            event.phase == ExecutionPhase.Running && previous.executionPhase != ExecutionPhase.Running &&
            previous.executionPhase != ExecutionPhase.RequiresAction
        if (started) runReplies[sessionId] = RunReply.None
        val reply = when {
            event is AgentEvent.RunFinished || event is AgentEvent.RunError ||
                event is AgentEvent.ExecutionPhaseChanged && event.phase in setOf(ExecutionPhase.Idle, ExecutionPhase.Lost) ->
                runReplies.remove(sessionId)
            else -> null
        }
        val unread = event is AgentEvent.RunFinished && !event.cancelled && (reply == RunReply.Chat || reply == RunReply.Report) &&
            sessionId !in viewing.values
        val unsettle = started || state.status == SessionStatus.Waiting && previous.status != SessionStatus.Waiting
        val inboxChanged = if (unread || unsettle) {
            val details = store.sessionDetails(agentId, sessionId)
            val next = details.copy(settled = details.settled && !unsettle, unread = details.unread || unread)
            (next != details).also { if (it) saveDetails(agentId, sessionId, next) }
        } else false
        if (state.queued.isNotEmpty() || state.running || state.reflectionId != null || state.executionPhase != null || state.entries.isNotEmpty() || state.pending.isNotEmpty() || state.children.isNotEmpty())
                live[sessionId] = state else live.remove(sessionId)
        if (event.isLive) deliver(SessionUpdate(agentId, sessionId, SessionChange.Event(event)))
        eventListeners.load().forEach { it(agentId, sessionId, event) }
        if (inboxChanged || state.status != previous.status) changed(store.session(agentId, sessionId).copy(status = state.status))
    }

    /**
     * A run that ends by cancellation (stopped, backgrounded, or the host's background budget expired) publishes no
     * terminal event of its own, so nothing would otherwise clear a stale `running` flag or a pending Approval,
     * Questions or Connect it left behind. Call once the run's job has fully finished; a no-op if the run already
     * published its own terminal event (e.g. it answered its last interaction and replied before it was cancelled).
     */
    suspend fun clearIfStopped(agentId: AgentId, sessionId: SessionId) = lock.withLock {
        val previous = live[sessionId] ?: return@withLock
        val resolved = previous.pending.filter { it.id in previous.interactionChildren }
        if (!previous.running && resolved.size == previous.pending.size) return@withLock
        val state = previous.copy(running = false, pending = resolved)
        if (state.queued.isNotEmpty() || state.reflectionId != null || state.executionPhase != null || state.entries.isNotEmpty() || state.pending.isNotEmpty() || state.children.isNotEmpty())
            live[sessionId] = state else live.remove(sessionId)
        deliver(SessionUpdate(agentId, sessionId, SessionChange.Live(state)))
        if (state.status != previous.status) changed(store.session(agentId, sessionId).copy(status = state.status))
    }

    /**
     * At startup no run can still be active, so a tool call a Session's run never answered (the process restarted
     * mid-turn, e.g. while waiting on an `mcp_login` sign-in) would otherwise stay unresolved forever: no result
     * means no further event ever arrives to tell a client it stopped running. [owned] names Sessions an external
     * harness already recovers on its own (its own draft/checkpoint bookkeeping handles those).
     */
    suspend fun recoverOrphanedToolCalls(owned: suspend (SessionId) -> Boolean) {
        for (agent in store.agents()) {
            for (info in store.sessions(agent.id)) {
                if (owned(info.id)) continue
                val entries = store.entries(agent.id, info.id)
                val answered = entries.filterIsInstance<SessionEntry.ToolResult>().mapTo(mutableSetOf()) { it.id }
                val orphaned = entries.filterIsInstance<SessionEntry.ToolCall>().filter { it.parent == null && it.id !in answered }
                for (call in orphaned)
                    append(agent.id, info.id, SessionEntry.ToolResult(call.id, call.name, "The Server restarted before this tool call finished.", isError = true, identity = call.identity))
            }
        }
    }

    /**
     * Sends [subscriber] the entries from [after] on (or a [SessionChange.Reset] window of the last [limit]) and the live
     * state, then keeps it subscribed.
     */
    suspend fun subscribe(agentId: AgentId, sessionId: SessionId, after: Int?, limit: Int, subscriber: Subscriber) = lock.withLock {
        // Reads the whole file to send a window of it; a DB fetches just the range.
        val entries = store.entries(agentId, sessionId)
        counts[sessionId] = entries.size
        subscribers.getOrPut(sessionId) { mutableSetOf() } += subscriber
        send(agentId, sessionId, entries, after, limit, subscriber)
    }

    private fun send(agentId: AgentId, sessionId: SessionId, entries: List<SessionEntry>, after: Int?, limit: Int, subscriber: Subscriber) {
        val from = if (after == null || after > entries.size || entries.size - after > limit) maxOf(0, entries.size - limit) else after
        if (from != after) logger.info { "session.resync session=${sessionId.value} after=$after entries=${entries.size} from=$from" }
        val changes = buildList {
            if (from != after) add(SessionChange.Reset(from))
            entries.drop(from).forEachIndexed { i, entry -> add(SessionChange.Entry(from + i, entry.forClient())) }
            val children = entries.filterIsInstance<SessionEntry.Notice>().filterNot { it.code == "sub_agent_delivery" }
                .mapNotNull { it.child?.forClient() }.associateBy { it.sessionId }
            add(SessionChange.Live((live[sessionId] ?: LiveState()).let { it.copy(children = children + it.children) }))
        }
        for (change in changes) if (!subscriber.offer(SessionUpdate(agentId, sessionId, change))) return dropped(sessionId, subscriber)
    }

    suspend fun unsubscribe(sessionId: SessionId, subscriber: Subscriber) = lock.withLock {
        subscribers[sessionId]?.remove(subscriber)
        Unit
    }

    suspend fun unsubscribeAll(subscriber: Subscriber) = lock.withLock { removeEverywhere(subscriber) }

    /** Up to [limit] entries before seq [before]. Entries never change, so this needs no lock. */
    suspend fun history(agentId: AgentId, sessionId: SessionId, before: Int, limit: Int): List<SessionChange.Entry> {
        // Reads the whole file for one page; a DB fetches just the range.
        val entries = store.entries(agentId, sessionId)
        val to = before.coerceIn(0, entries.size)
        val from = maxOf(0, to - limit)
        return entries.subList(from, to).mapIndexed { i, entry -> SessionChange.Entry(from + i, entry.forClient()) }
    }

    private fun statusOf(sessionId: SessionId) = live[sessionId]?.status ?: SessionStatus.Idle

    private fun removeEverywhere(subscriber: Subscriber) {
        viewing.remove(subscriber)
        subscribers.values.forEach { it.remove(subscriber) }
    }

    private suspend fun count(agentId: AgentId, sessionId: SessionId): Int {
        return store.entryCount(agentId, sessionId).also { counts[sessionId] = it }
    }

    private fun deliver(update: SessionUpdate) {
        subscribers[update.sessionId]?.toList()?.forEach { if (!it.offer(update)) dropped(update.sessionId, it) }
    }

    private fun dropped(sessionId: SessionId, subscriber: Subscriber) {
        logger.warn { "session.subscriber.dropped session=${sessionId.value}: it couldn't keep up or has gone" }
        removeEverywhere(subscriber)
    }

    private fun changed(info: SessionInfo) = changeListeners.load().forEach { it(info) }

    private companion object {
        const val RESYNC_LIMIT = 100
    }
}

internal fun SubAgentState.forClient() = if (private && !delivered && result != null) copy(result = null) else this

private fun SessionEntry.forClient(): SessionEntry = if (this is SessionEntry.Notice)
    copy(child = child?.forClient(), draft = draft.map { it.forClient() }) else this

internal const val MAX_SESSION_TITLE = 100
