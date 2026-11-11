package kinuvo.app.session

import kinuvo.protocol.SessionInfo

/** A row of an Agent's Sessions: a Session, with the [earlier] runs of the same Routine folded under its latest. */
data class SessionGroup(val session: SessionInfo, val earlier: List<SessionInfo> = emptyList())

/** Keeps the order, newest first, folding each Routine's earlier runs under its latest run. */
fun List<SessionInfo>.groupRoutineRuns(): List<SessionGroup> {
    val runs = filter { it.routine != null }.groupBy { it.routine!!.id }
    return mapNotNull { s ->
        val routine = s.routine ?: return@mapNotNull SessionGroup(s)
        val all = runs.getValue(routine.id)
        if (all.first().id != s.id) null else SessionGroup(s, all.drop(1))
    }
}

/** Sessions whose title has every word of [query], ignoring case. */
fun List<SessionInfo>.search(query: String): List<SessionInfo> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return this
    return filter { s -> s.title.lowercase().let { t -> words.all(t::contains) } }
}
