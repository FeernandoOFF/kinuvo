package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.computerRoutes() {
    handle(Api.Computers) { server.harness.computers.list() }
    handle(Api.WatchComputer) { error("Use bounded screens.watch") }
    handle(Api.ComputerInput) { error("Use frame-bound screens.input") }
    handle(Api.Takeover) { error("Use generation-bound screens.control") }
}
