package kinuvo.server

import kinuvo.protocol.Frame

/** Who may call a route: [Management] needs management access, [Device] any paired Device, [Linked] only another Server's execution link, [DeviceOrLinked] either. */
enum class RouteAccess(val management: Boolean, val linked: Boolean) {
    Management(true, false),
    Device(false, false),
    Linked(false, true),
    DeviceOrLinked(false, true),
}

/** One request being answered: the Server's services and the connection it arrived on. */
class Call(val server: ServerServices, val caller: Caller, val requestId: String)

/** Answers requests by method. `server/rpc` implements it; [ServerRuntime] keeps the connection, authentication and frames. */
interface Routes {
    /** Every method with a route. */
    val methods: Set<String>

    fun access(method: String): RouteAccess?

    suspend fun dispatch(call: Call, request: Frame.Request): Frame.Response
}
