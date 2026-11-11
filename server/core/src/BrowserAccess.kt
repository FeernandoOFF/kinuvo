package kinuvo.server

import kinuvo.protocol.browser.*
import kinuvo.server.execution.ExecutionRegistry

/** The Browser of an Agent this Server runs, for clients. */
class BrowserAccess internal constructor(private val harness: HarnessRuntime, private val registry: ExecutionRegistry, private val admin: Admin) {
    private fun service() = harness.browser ?: throw ServerError.Unavailable("Browser is unavailable on this Server")

    suspend fun validate(owner: BrowserOwner) {
        if (registry.registration(owner.agentId) != null) throw ServerError.Unavailable("Browser is managed by this Agent's harness and isn't available in Kinuvo")
        admin.agent(owner.agentId)
    }

    suspend fun presentation(owner: BrowserOwner): BrowserPresentation {
        validate(owner)
        return service().presentation(owner)
    }

    suspend fun savePresentation(request: BrowserPresentationRequest): BrowserPresentation {
        validate(request.owner)
        return service().presentation(request)
    }

    suspend fun tabs(owner: BrowserOwner): List<BrowserTab> {
        validate(owner)
        return service().tabs(owner)
    }

    suspend fun command(command: BrowserCommand): Nothing {
        validate(command.owner)
        throw ServerError.Unavailable("Use screens.control and frame-bound screens.input/command")
    }
}
