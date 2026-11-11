package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise

import kinuvo.protocol.browser.*
import kinuvo.server.execution.ExecutionRegistry

/** The Browser of an Agent this Server runs, for clients. */
class BrowserAccess internal constructor(private val harness: HarnessRuntime, private val registry: ExecutionRegistry, private val admin: Admin) {
    context(_: Raise<BrowserError>)
    private fun service() = harness.browser ?: raise(BrowserError.Unavailable)

    context(_: Raise<BrowserError>, _: Raise<AgentError>)
    suspend fun validate(owner: BrowserOwner) {
        if (registry.registration(owner.agentId) != null) raise(BrowserError.Managed)
        admin.agent(owner.agentId)
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
