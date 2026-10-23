package kinuvo.harness.server

import kinuvo.harness.core.RoomOutcome
import kinuvo.protocol.RoomMessage

/** One root's in-process inboxes and request ledger. No private Agent material belongs here. */
internal class RoomWork(val root: RoomMessage, private val members: Set<String>, private val limits: Rooms.Limits) {
    data class Inbox(val agentId: String, var depth: Int = 0, val results: MutableList<String> = mutableListOf())
    enum class State { Open, Completed, Declined }
    data class Request(val id: String, val owner: String, val target: String, val text: String, val wait: Boolean, val depth: Int, var state: State = State.Open, var messageId: String? = null)
    val pending = linkedMapOf<String, Inbox>().apply { root.recipients.forEach { put(it, Inbox(it)) } }
    val requests = linkedMapOf<String, Request>()
    val counts = mutableMapOf<String, Int>()
    private val completed = mutableSetOf<String>()
    private var current: String? = null
    var dispatched = 0
        private set
    var rejection: String? = null
        private set

    fun take(): Inbox {
        check(dispatched < limits.dispatches) { "Room participation limit reached" }
        val next = pending.values.first()
        pending.remove(next.agentId)
        check(counts.getOrDefault(next.agentId, 0) < limits.perMember) { "Room member participation limit reached" }
        counts[next.agentId] = counts.getOrDefault(next.agentId, 0) + 1
        dispatched++
        current = next.agentId
        return next
    }

    fun incoming(agent: String) = requests.values.filter { it.target == agent && it.state == State.Open }

    private fun capacity(work: Inbox, outcome: RoomOutcome.Request) {
        val request = Request(outcome.id, work.agentId, outcome.target, outcome.text, outcome.wait, work.depth + 1)
        val scheduled = pending.mapValues { it.value.depth }.toMutableMap()
        scheduled[request.target] = maxOf(scheduled[request.target] ?: 0, request.depth)
        // Open waits reserve a later turn even if their owner already has unrelated pending work.
        // One target settles all its inbox requests together, coalescing waits from the same owner.
        val waiting = (requests.values + request).filter { it.wait && it.state == State.Open }
            .groupBy { it.owner to it.target }.values.map { group -> group.first().owner to group.maxOf { it.depth } }
        val reserved = scheduled.map { it.key to it.value } + waiting
        if (reserved.any { it.second > limits.depth } ||
            reserved.groupingBy { it.first }.eachCount().any { (agent, turns) -> counts.getOrDefault(agent, 0) + turns > limits.perMember } ||
            dispatched + reserved.size > limits.dispatches) {
            rejection = "Room participation limit reached. Send a new message to continue."
            error(rejection!!)
        }
    }

    fun validate(work: Inbox, outcome: RoomOutcome) {
        when (outcome) {
            is RoomOutcome.Request -> {
                require(outcome.target in members && outcome.target != work.agentId) { "Address another available member of this Room" }
                val previous = requests[outcome.id]
                if (previous != null) {
                    require(previous.owner == work.agentId && previous.target == outcome.target && previous.text == outcome.text && previous.wait == outcome.wait) { "Request ID already belongs to different work" }
                    require(incoming(work.agentId).isEmpty()) { "Return or pass incoming requests before repeating earlier work" }
                    return
                }
                require(incoming(work.agentId).isEmpty() || outcome.wait) { "Return your incoming results, pass, or wait for the delegated result" }
                capacity(work, outcome)
                require(requests.size < limits.dispatches) { "Room request limit reached" }
            }
            is RoomOutcome.Result -> {
                require(outcome.ids.isNotEmpty() && outcome.ids.distinct() == outcome.ids) { "Identify the open requests being fulfilled" }
                require(outcome.ids.all { requests[it]?.let { r -> r.target == work.agentId && r.state == State.Open } == true }) { "Result must fulfill your open requests in this root" }
                require(incoming(work.agentId).all { it.id in outcome.ids }) { "Fulfill all requests in your inbox together" }
            }
            is RoomOutcome.Reply -> require(incoming(work.agentId).isEmpty()) { "Use return_result to fulfill the requests in your inbox" }
            RoomOutcome.Pass -> Unit
        }
    }

    fun finish(work: Inbox, outcome: RoomOutcome, messageId: String?) {
        current = null
        completed += work.agentId
        when (outcome) {
            is RoomOutcome.Request -> if (outcome.id !in requests) {
                val request = Request(outcome.id, work.agentId, outcome.target, outcome.text, outcome.wait, work.depth + 1, messageId = messageId)
                requests[request.id] = request
                pending.getOrPut(request.target) { Inbox(request.target, request.depth) }.let { it.depth = maxOf(it.depth, request.depth) }
            }
            is RoomOutcome.Result -> outcome.ids.forEach { settle(requests.getValue(it), State.Completed) }
            RoomOutcome.Pass -> incoming(work.agentId).forEach { settle(it, State.Declined) }
            is RoomOutcome.Reply -> Unit
        }
    }

    private fun settle(request: Request, state: State) {
        request.state = state
        if (request.wait) {
            pending.getOrPut(request.owner) { Inbox(request.owner, request.depth) }.let {
                it.depth = maxOf(it.depth, request.depth)
                it.results += request.id
            }
        }
    }

    fun state() = members.joinToString("\n") { member ->
        val status = when {
            member == current -> "active"
            member in pending -> "pending"
            requests.values.any { it.owner == member && it.wait && it.state == State.Open } -> "waiting"
            member in completed -> "completed"
            else -> "not scheduled"
        }
        "$member: $status (${counts.getOrDefault(member, 0)} turns used)"
    }
}
