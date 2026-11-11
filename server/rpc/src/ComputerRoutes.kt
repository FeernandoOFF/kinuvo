package kinuvo.server.rpc

import kinuvo.protocol.Api
import arrow.core.raise.context.raise
import kinuvo.server.ComputerError

internal fun RouteBuilder.computerRoutes() {
    handle(Api.Computers) { server.harness.computers.list() }
    handle(Api.WatchComputer) { raise(ComputerError.UseWatch) }
    handle(Api.ComputerInput) { raise(ComputerError.UseInput) }
    handle(Api.Takeover) { raise(ComputerError.UseControl) }
}
