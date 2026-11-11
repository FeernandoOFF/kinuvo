package kinuvo.server

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
    suspend fun list(agentId: AgentId) = harness.routines.list(admin.agent(agentId))

    suspend fun create(p: CreateRoutineParams): Routine {
        val agent = admin.agent(p.agentId)
        return harness.routines.create(agent, checkSession(agent, p.spec))
    }

    suspend fun update(p: UpdateRoutineParams): Routine {
        val agent = admin.agent(p.agentId)
        val spec = checkSession(agent, p.spec)
        return harness.routines.update(agent, p.routineId) { it.with(spec) } ?: throw ServerError.NotFound("No routine '${p.routineId}'")
    }

    suspend fun run(p: RoutineParams): Routine {
        if (!scheduling()) throw ServerError.Unavailable("No server is running to run it; start one with `kinuvo serve` or the desktop app")
        return harness.routines.update(admin.agent(p.agentId), p.routineId) { it.copy(runRequested = true) } ?: throw ServerError.NotFound("No routine '${p.routineId}'")
    }

    suspend fun remove(p: RoutineParams) {
        if (!harness.routines.remove(admin.agent(p.agentId), p.routineId)) throw ServerError.NotFound("No routine '${p.routineId}'")
    }

    private suspend fun checkSession(agent: Agent, spec: RoutineSpec) = spec.also {
        val id = it.session?.takeIf { s -> s != NEW_SESSIONS } ?: return@also
        sessions.requireSession(agent.id, SessionId(id))
    }
}
