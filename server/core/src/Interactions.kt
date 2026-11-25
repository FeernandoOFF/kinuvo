package kinuvo.server

import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.RespondParams
import kinuvo.protocol.session.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The open interactions of each Session run, answered together: an answer is staged and shown to every Device, and the
 * waiting work is released only once every open interaction has one. One claim under [lock] settles each batch of
 * answers and each [settle]ment, so an answer, a timeout and a duplicate can't both win.
 */
class InteractionSets(private val log: SessionLog) {
    private class Staged(val answer: RespondParams, val release: suspend (RespondParams) -> Boolean)

    private val lock = Mutex()
    private val staged = LinkedHashMap<InteractionId, Staged>()
    private val claimed = mutableMapOf<InteractionId, SessionId>()
    private val stopped = mutableMapOf<SessionId, MutableSet<InteractionId>>()

    init {
        log.onInteractionResolved = { session, id -> resolved(session, id) }
    }

    /**
     * Stages [answer]; [release] hands it to the waiting work. False when it is a duplicate, late or unknown. Without a
     * [grouped] run parked on the set, e.g. for a Workflow Approval, the answer is released at once.
     */
    suspend fun respond(answer: RespondParams, grouped: Boolean, release: suspend (RespondParams) -> Boolean): Boolean {
        val session = answer.sessionId
        val batch = lock.withLock {
            if (unclaimed(session).none { it.id == answer.requestId }) return false
            if (answer.requestId in stopped[session].orEmpty()) return false
            if (answer.requestId in staged) return false
            if (!grouped) return@withLock null
            staged[answer.requestId] = Staged(answer, release)
            log.staged(session, answer.requestId)
            claimCovered(session)
        } ?: return release(answer)
        return releaseAll(batch, answer.requestId)
    }

    /** Runs [action] to settle [id] without an answer, e.g. a timeout; false when an answer is staged or [action] lost the race. */
    suspend fun settle(id: InteractionId, action: () -> Boolean): Boolean = lock.withLock { id !in staged && id !in claimed && action() }

    /** Whether [sessionId] has an interaction that nobody has settled yet. */
    suspend fun open(sessionId: SessionId): Boolean = log.open(sessionId).isNotEmpty()

    suspend fun awaitClear(sessionId: SessionId) {
        while (true) {
            val version = log.interactionsChanged.value
            if (!open(sessionId)) return
            log.interactionsChanged.first { it != version }
        }
    }

    /**
     * Drops answers waiting on siblings, e.g. when the run is stopped and its work is cancelled instead. Interactions open
     * now can no longer be answered or released, so a final answer racing the Stop can't release work after it.
     */
    suspend fun stop(sessionId: SessionId) {
        lock.withLock {
            staged.values.removeAll { it.answer.sessionId == sessionId }
            val open = unclaimed(sessionId).mapTo(mutableSetOf()) { it.id }
            if (open.isNotEmpty()) stopped[sessionId] = open
        }
        log.stopping(sessionId)
    }

    private suspend fun resolved(session: SessionId, id: InteractionId) {
        val batch = lock.withLock {
            staged -= id
            claimed -= id
            claimCovered(session)
        }
        releaseAll(batch, null)
    }

    private suspend fun unclaimed(session: SessionId): List<PendingInteraction> {
        val all = log.open(session)
        val ids = all.mapTo(mutableSetOf()) { it.id }
        claimed.entries.removeAll { it.value == session && it.key !in ids }
        stopped[session]?.let { it.retainAll(ids); if (it.isEmpty()) stopped -= session }
        staged.values.removeAll { it.answer.sessionId == session && it.answer.requestId !in ids }
        return all.filter { it.id !in claimed }
    }

    private suspend fun claimCovered(session: SessionId): List<Staged> {
        val open = unclaimed(session)
        val blocked = stopped[session].orEmpty()
        if (open.isEmpty() || open.any { it.id !in staged || it.id in blocked }) return emptyList()
        return open.map { requireNotNull(staged.remove(it.id)) }.also { batch -> batch.forEach { claimed[it.answer.requestId] = session } }
    }

    private suspend fun releaseAll(batch: List<Staged>, own: InteractionId?): Boolean {
        var failure: Exception? = null
        var released = true
        for ((index, item) in batch.withIndex()) {
            val ok = try { item.release(item.answer) }
            catch (e: CancellationException) { unclaim(batch.drop(index)); throw e }
            catch (e: Exception) {
                if (failure == null) failure = e
                false
            }
            if (!ok) {
                unclaim(listOf(item))
                if (item.answer.requestId == own) released = false
            }
        }
        failure?.let { throw it }
        return released
    }

    private suspend fun unclaim(items: List<Staged>) = withContext(NonCancellable) {
        lock.withLock { items.forEach { claimed -= it.answer.requestId } }
    }
}
