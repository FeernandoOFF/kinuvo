package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise

import kinuvo.harness.Agent
import kinuvo.protocol.browser.*
import kinuvo.server.execution.ExecutionRegistry
import kinuvo.server.execution.serverToolAgent

/** The Browser of an Agent this Server runs, for clients. */
class BrowserAccess internal constructor(private val harness: AgentRuntime, private val registry: ExecutionRegistry, private val admin: Admin) {
    context(_: Raise<BrowserError>)
    private fun service() = harness.browser ?: raise(BrowserError.Unavailable)

    context(_: Raise<BrowserError>, _: Raise<AgentError>)
    suspend fun validate(owner: BrowserOwner): Agent {
        val registration = registry.registration(owner.agentId) ?: return admin.agent(owner.agentId)
        if (registration.remoteAgentId != null) raise(BrowserError.Managed)
        return registration.serverToolAgent()
    }

    context(_: Raise<BrowserError>, _: Raise<AgentError>)
    suspend fun presentation(owner: BrowserOwner): BrowserPresentation {
        validate(owner)
        return service().presentation(owner)
    }

    context(_: Raise<BrowserError>, _: Raise<AgentError>)
    suspend fun savePresentation(request: BrowserPresentationRequest): BrowserPresentation {
        validate(request.owner)
        return service().presentation(request)
    }

    context(_: Raise<BrowserError>, _: Raise<AgentError>)
    suspend fun tabs(owner: BrowserOwner): List<BrowserTab> {
        validate(owner)
        return service().tabs(owner)
    }

    context(_: Raise<BrowserError>, _: Raise<AgentError>)
    suspend fun command(command: BrowserCommand): Nothing {
        validate(command.owner)
        raise(BrowserError.UseScreens)
    }
}
