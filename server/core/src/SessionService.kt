package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.*

/** Changes to a Session's settings and details, for Sessions that exist. */
class SessionService internal constructor(private val harness: HarnessRuntime, private val admin: Admin) {
    context(_: Raise<SessionError>)
    suspend fun requireSession(agentId: AgentId, sessionId: SessionId) {
        if (harness.store.entries(agentId, sessionId).isEmpty()) raise(SessionError.NotFound(sessionId))
    }

    context(_: Raise<AgentError>, _: Raise<SessionError>)
    suspend fun configure(p: ConfigureSessionParams) = admin.agent(p.agentId).let { agent ->
        requireSession(agent.id, p.sessionId)
        harness.configure(agent, p.sessionId, p.settings)
    }

    context(_: Raise<SessionError>)
    suspend fun update(p: UpdateSessionParams) = run {
        requireSession(p.agentId, p.sessionId)
        harness.log.updateDetails(p.agentId, p.sessionId, p.title, p.favorite)
    }
}
