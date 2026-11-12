package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.screenRoutes() {
    handle(Api.ScreenOpen) { caller.screens.open(caller.requireDevice(), it) }
    handle(Api.Screens) { caller.screens.list(it) }
    handle(Api.ScreenWatch) { caller.screens.watch(it) }
    handle(Api.ScreenUnwatch) { caller.screens.unwatch(it); caller.unwatchScreen(it) }
    handle(Api.ScreenAck) { caller.screens.ack(it) }
    handle(Api.ScreenControl) { caller.screens.control(caller.requireDevice(), it) }
    handle(Api.ScreenInput) { caller.screens.input(caller.requireDevice(), it) }
    handle(Api.ScreenCommand) { caller.screens.command(caller.requireDevice(), it) }
}
