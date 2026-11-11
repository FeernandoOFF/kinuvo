package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise

import kinuvo.harness.Agent
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.routine.*
import kinuvo.protocol.session.SessionId
import kinuvo.server.routines.NEW_SESSIONS
import kinuvo.server.routines.with

/** An Agent's Routines, checked against its Sessions and whether a Server is running to run them. */
class RoutineService internal constructor(
    private val harness: HarnessRuntime,
    private val admin: Admin,
    private val sessions: SessionService,
    private val scheduling: () -> Boolean,
) {
    context(_: Raise<AgentError>)
    suspend fun list(agentId: AgentId) = harness.routines.list(admin.agent(agentId))

    context(_: Raise<AgentError>, _: Raise<SessionError>)
    suspend fun create(p: CreateRoutineParams): Routine {
        val agent = admin.agent(p.agentId)
        return harness.routines.create(agent, checkSession(agent, p.spec))
    }

    context(_: Raise<AgentError>, _: Raise<SessionError>, _: Raise<RoutineError>)
    suspend fun update(p: UpdateRoutineParams): Routine {
        val agent = admin.agent(p.agentId)
        val spec = checkSession(agent, p.spec)
        return harness.routines.update(agent, p.routineId) { it.with(spec) } ?: raise(RoutineError.NotFound(p.routineId))
    }

    context(_: Raise<AgentError>, _: Raise<RoutineError>)
    suspend fun run(p: RoutineParams): Routine {
        if (!scheduling()) raise(RoutineError.NoScheduler)
        return harness.routines.update(admin.agent(p.agentId), p.routineId) { it.copy(runRequested = true) } ?: raise(RoutineError.NotFound(p.routineId))
    }

    context(_: Raise<AgentError>, _: Raise<RoutineError>)
    suspend fun remove(p: RoutineParams) {
        if (!harness.routines.remove(admin.agent(p.agentId), p.routineId)) raise(RoutineError.NotFound(p.routineId))
    }

    context(_: Raise<SessionError>)
    private suspend fun checkSession(agent: Agent, spec: RoutineSpec) = spec.also {
        val id = it.session?.takeIf { s -> s != NEW_SESSIONS } ?: return@also
        sessions.requireSession(agent.id, SessionId(id))
    }
}
