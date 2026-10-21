package kinuvo.harness.core.routines

import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import kinuvo.harness.core.RunSession
import kinuvo.harness.core.chatTurn
import kinuvo.harness.core.tools.ToolExecutor
import kinuvo.protocol.SessionEntry

/** Gather the run's context, work through the shared tool loop, decide the outcome, deliver it. */
internal fun routineStrategy(session: RunSession, executor: ToolExecutor, run: RoutineRun, prompt: String, stopped: (String) -> Unit) = strategy<Unit, Unit>("kinuvoRoutine") {
    val gather by node<Unit, Unit>("gather") { session.append(SessionEntry.UserMessage(run.message(prompt), run.ref)) }
    val decide by node<Unit, Unit>("decide") { run.decide(session.text()) }
    val deliver by node<Unit, Unit>("deliver") {
        val outcome = checkNotNull(run.outcome)
        session.append(outcome)
        if (outcome.report) run.notify(outcome)
    }
    edge(nodeStart forwardTo gather)
    edge(gather forwardTo chatTurn(session, executor, stopped, decide))
    edge(decide forwardTo deliver)
    edge(deliver forwardTo nodeFinish)
}
