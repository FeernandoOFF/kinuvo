package kinuvo.harness.core

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import kinuvo.harness.core.tools.ToolExecutor

/** Accepted only after the Room validates the tool call under the usual Approval policy. */
sealed interface RoomOutcome {
    data class Reply(val text: String) : RoomOutcome
    data object Pass : RoomOutcome
    data class Request(val id: String, val target: String, val text: String, val wait: Boolean) : RoomOutcome
    data class Result(val ids: List<String>, val text: String) : RoomOutcome
}

class RoomRun(val beforeRequest: suspend () -> Unit = {}) {
    var outcome: RoomOutcome? = null
        private set
    var awaitingUser: String? = null
        private set

    fun complete(outcome: RoomOutcome) {
        check(this.outcome == null) { "Room turn already completed" }
        this.outcome = outcome
    }

    internal fun needsUser(id: String?) { awaitingUser = id }
}

internal fun roomStrategy(session: RunSession, executor: ToolExecutor, run: RoomRun, stopped: (String) -> Unit) = strategy<Unit, Unit>("kinuvoRoom") {
    val complete by node<Unit, Unit>("completeRoomTurn") {
        checkNotNull(run.outcome) { "No Room outcome: use public_reply, return_result, address_member or pass_turn" }
    }
    edge(nodeStart forwardTo chatTurn(session, executor, stopped, complete, run))
    edge(complete forwardTo nodeFinish)
}
