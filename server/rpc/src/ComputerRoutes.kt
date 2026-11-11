package kinuvo.server.rpc

import kinuvo.protocol.Api
import kinuvo.server.ServerError

internal fun RouteBuilder.computerRoutes() {
    handle(Api.Computers) { server.harness.computers.list() }
    handle(Api.WatchComputer) { throw ServerError.Unavailable("Use bounded screens.watch") }
    handle(Api.ComputerInput) { throw ServerError.Unavailable("Use frame-bound screens.input") }
    handle(Api.Takeover) { throw ServerError.Unavailable("Use generation-bound screens.control") }
}
