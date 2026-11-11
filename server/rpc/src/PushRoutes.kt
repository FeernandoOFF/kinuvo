package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.pushRoutes() {
    device(Api.SubscribePush) { caller.setPush(it.subscription) }
}
