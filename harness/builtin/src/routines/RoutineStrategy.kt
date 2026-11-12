package kinuvo.harness.builtin.routines

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import kinuvo.harness.builtin.RunSession
import kinuvo.harness.builtin.chatTurn
import kinuvo.harness.builtin.ToolCalls
import kinuvo.harness.RoutineTurn
import kinuvo.protocol.SessionEntry

/** Gather the run's context, work through the shared tool loop, decide the outcome, deliver it. */
internal fun routineStrategy(session: RunSession, calls: ToolCalls, run: RoutineTurn, prompt: String) = strategy<Unit, Unit>("kinuvoRoutine") {
    val gather by node<Unit, Unit>("gather") { session.append(SessionEntry.UserMessage(run.message(prompt), run.ref)) }
    val decide by node<Unit, Unit>("decide") { run.decide(session.text()) }
    val deliver by node<Unit, Unit>("deliver") {
        val outcome = checkNotNull(run.outcome)
        session.append(outcome)
        if (outcome.report) run.notify(outcome)
    }
    edge(nodeStart forwardTo gather)
    edge(gather forwardTo chatTurn(session, calls, decide))
    edge(decide forwardTo deliver)
    edge(deliver forwardTo nodeFinish)
}
