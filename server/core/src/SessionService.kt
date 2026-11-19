package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.execution.ExecutionConnection
import kinuvo.server.execution.ExecutionCoordinator
import kinuvo.server.execution.serverToolAgent
import kinuvo.protocol.session.*

/** Changes to a Session's settings and details, for Sessions that exist. */
class SessionService internal constructor(private val harness: AgentRuntime, private val admin: Admin, private val external: ExecutionCoordinator) {
    context(_: Raise<SessionError>)
    suspend fun requireSession(agentId: AgentId, sessionId: SessionId) {
        if (harness.store.entries(agentId, sessionId).isEmpty()) raise(SessionError.NotFound(sessionId))
    }

    context(_: Raise<AgentError>, _: Raise<SessionError>)
    suspend fun configure(p: ConfigureSessionParams): SessionInfo {
        if (external.owns(p.sessionId)) {
            external.session(p.agentId, p.sessionId)
            val saved = external.record(p.sessionId)
            require(saved.connection.connection is ExecutionConnection.Acp) { "Session settings belong to the remote Server" }
            require(p.settings == SessionSettings(approvals = p.settings.approvals)) { "Only Risk tolerance is configured for ACP Sessions" }
            return harness.configure(saved.registration.serverToolAgent(), p.sessionId, p.settings)
        }
        val agent = admin.agent(p.agentId)
        requireSession(agent.id, p.sessionId)
        return harness.configure(agent, p.sessionId, p.settings)
    }

    context(_: Raise<SessionError>)
    suspend fun update(p: UpdateSessionParams) = run {
        requireSession(p.agentId, p.sessionId)
        harness.log.updateDetails(p.agentId, p.sessionId, p.title, p.favorite, p.settled, p.seen)
    }
}
