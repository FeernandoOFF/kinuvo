package kinuvo.server

import kinuvo.server.store.Store
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.LiveState
import kinuvo.protocol.approval.PendingInteraction
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
import kinuvo.protocol.session.isLive
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

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
    suspend fun completion(sessionId: SessionId): AgentEvent? = lock.withLock { completions[sessionId] }
    private val subscribers = HashMap<SessionId, MutableSet<Subscriber>>()
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

    suspend fun pending(sessionId: SessionId): List<PendingInteraction> = lock.withLock { live[sessionId]?.pending.orEmpty() }

    suspend fun status(sessionId: SessionId): SessionStatus = lock.withLock { statusOf(sessionId) }

    var executionMetadata: suspend (SessionInfo) -> SessionInfo = { it }

    override suspend fun session(agentId: AgentId, sessionId: SessionId): SessionInfo = executionMetadata(store.session(agentId, sessionId).copy(status = status(sessionId)))

    /** [before] is the last Session ID of the previous page; [routineId] picks that Routine's Sessions instead of the chats. */
    suspend fun page(agentId: AgentId, before: String?, limit: Int, routineId: String? = null): SessionPage {
        val all = lock.withLock { store.sessions(agentId).map { executionMetadata(it).copy(status = statusOf(it.id)) } }
            .sortedWith(SessionOrder)
            .filter { !it.hidden }.filter { if (routineId == null) it.inChats else it.routine?.id == routineId }
        val from = before?.let { id -> all.indexOfFirst { it.id.value == id } + 1 } ?: 0
        val page = all.drop(from).take(limit)
        return SessionPage(page, from + page.size < all.size, if (before == null && routineId == null) all.filter { it.favorite } else emptyList())
    }

    override suspend fun createSession(agentId: AgentId, instructions: String, routine: RoutineRef?): SessionInfo =
        store.createSession(agentId, instructions, routine).also { info -> lock.withLock { changed(info) } }

    suspend fun createHidden(agentId: AgentId, parent: SessionParent, settings: SessionSettings): SessionInfo = lock.withLock {
        val info = store.createSession(agentId, "")
        store.append(agentId, info.id, SessionEntry.Settings(settings))
        store.append(agentId, info.id, SessionEntry.Details(parent = parent, hidden = true))
        store.session(agentId, info.id).also { changed(it) }
    }

    var onDelete: suspend (AgentId, SessionId) -> Unit = { _, _ -> }
    override suspend fun deleteSession(agentId: AgentId, sessionId: SessionId) {
        store.entries(agentId, sessionId)
        onDelete(agentId, sessionId)
        lock.withLock {
        store.deleteSession(agentId, sessionId)
        counts -= sessionId
        live -= sessionId
        completions -= sessionId
        subscribers -= sessionId
        Unit
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

    suspend fun updateDetails(agentId: AgentId, sessionId: SessionId, title: String?, favorite: Boolean?): SessionInfo = lock.withLock {
        val previous = store.entries(agentId, sessionId).filterIsInstance<SessionEntry.Details>().lastOrNull() ?: SessionEntry.Details()
        val next = previous.copy(writtenAt = clock(), title = if (title == null) previous.title else title.trim().take(MAX_SESSION_TITLE).ifEmpty { null }, favorite = favorite ?: previous.favorite)
        val seq = count(agentId, sessionId)
        store.append(agentId, sessionId, next)
        saved(agentId, sessionId, seq, next)
        store.session(agentId, sessionId).copy(status = statusOf(sessionId)).also { changed(it) }
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
        live[sessionId]?.let {
            val state = it.on(entry)
            if (state.running || state.reflectionId != null || state.executionPhase != null || state.entries.isNotEmpty() || state.pending.isNotEmpty() || state.children.isNotEmpty())
                live[sessionId] = state else live.remove(sessionId)
        }
        deliver(SessionUpdate(agentId, sessionId, SessionChange.Entry(seq, entry)))
    }

    private suspend fun metadata(agentId: AgentId, sessionId: SessionId, entries: List<SessionEntry>) {
        if (entries.any { it is SessionEntry.UserMessage || it is SessionEntry.Settings || it is SessionEntry.Details })
            changed(store.session(agentId, sessionId).copy(status = statusOf(sessionId)))
    }

    suspend fun publish(agentId: AgentId, sessionId: SessionId, event: AgentEvent) = lock.withLock {
        store.entries(agentId, sessionId)
        when(event) {
            is AgentEvent.RunStarted -> completions.remove(sessionId)
            is AgentEvent.RunFinished, is AgentEvent.RunError -> completions[sessionId] = event
            else -> Unit
        }
        val before = statusOf(sessionId)
        val state = (live[sessionId] ?: LiveState()).on(event)
        if (state.running || state.reflectionId != null || state.executionPhase != null || state.entries.isNotEmpty() || state.pending.isNotEmpty() || state.children.isNotEmpty())
                live[sessionId] = state else live.remove(sessionId)
        if (event.isLive) deliver(SessionUpdate(agentId, sessionId, SessionChange.Event(event)))
        eventListeners.load().forEach { it(agentId, sessionId, event) }
        if (state.status != before) changed(store.session(agentId, sessionId).copy(status = state.status))
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
        val changes = buildList {
            if (from != after) add(SessionChange.Reset(from))
            entries.drop(from).forEachIndexed { i, entry -> add(SessionChange.Entry(from + i, entry)) }
            val children = entries.filterIsInstance<SessionEntry.Notice>().mapNotNull { it.child }.associateBy { it.sessionId }
            add(SessionChange.Live((live[sessionId] ?: LiveState()).let { it.copy(children = children + it.children) }))
        }
        for (change in changes) if (!subscriber.offer(SessionUpdate(agentId, sessionId, change))) return removeEverywhere(subscriber)
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
        return entries.subList(from, to).mapIndexed { i, entry -> SessionChange.Entry(from + i, entry) }
    }

    private fun statusOf(sessionId: SessionId) = live[sessionId]?.status ?: SessionStatus.Idle

    private fun removeEverywhere(subscriber: Subscriber) = subscribers.values.forEach { it.remove(subscriber) }

    private suspend fun count(agentId: AgentId, sessionId: SessionId): Int {
        val entries = store.entries(agentId, sessionId)
        return entries.size.also { counts[sessionId] = it }
    }

    private fun deliver(update: SessionUpdate) {
        subscribers[update.sessionId]?.toList()?.forEach { if (!it.offer(update)) removeEverywhere(it) }
    }

    private fun changed(info: SessionInfo) = changeListeners.load().forEach { it(info) }

    private companion object {
        const val RESYNC_LIMIT = 100
    }
}

internal const val MAX_SESSION_TITLE = 100
