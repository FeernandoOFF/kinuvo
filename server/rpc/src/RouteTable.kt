package kinuvo.server.rpc

import arrow.core.raise.Raise
import kinuvo.protocol.Frame
import kinuvo.protocol.Method
import kinuvo.server.AccessError
import kinuvo.server.Call
import kinuvo.server.RouteAccess
import kinuvo.server.Routes
import kinuvo.server.ServerError
import kinuvo.server.failure
import kinuvo.server.respond

internal class Route(val access: RouteAccess, val ordered: Boolean, val handle: suspend (Call, Frame.Request) -> Frame.Response)

private class RouteTable(private val routes: Map<String, Route>) : Routes {
    override val methods get() = routes.keys

    override fun access(method: String) = routes[method]?.access

    override fun ordered(method: String) = routes[method]?.ordered == true

    override suspend fun dispatch(call: Call, request: Frame.Request) =
        routes[request.method]?.handle?.invoke(call, request) ?: request.failure(AccessError.UnknownMethod(request.method))
}

/** Registers one handler per [Method]; a method registered twice fails the build. An `ordered` route runs on the connection's loop in arrival order; the rest run concurrently. */
class RouteBuilder internal constructor() {
    private val routes = LinkedHashMap<String, Route>()

    fun <P, R> route(method: Method<P, R>, access: RouteAccess, ordered: Boolean = false, handler: suspend context(Raise<ServerError>) Call.(P) -> R) {
        require(routes.put(method.name, Route(access, ordered) { call, request -> respond(request, method) { call.handler(it) } }) == null) { "Duplicate route for ${method.name}" }
    }

    /** A route that needs management access. */
    fun <P, R> handle(method: Method<P, R>, ordered: Boolean = false, handler: suspend context(Raise<ServerError>) Call.(P) -> R) = route(method, RouteAccess.Management, ordered, handler)

    /** A route any paired Device may call. */
    fun <P, R> device(method: Method<P, R>, ordered: Boolean = false, handler: suspend context(Raise<ServerError>) Call.(P) -> R) = route(method, RouteAccess.Device, ordered, handler)

    /** A route only another Server's execution link may call. */
    fun <P, R> linked(method: Method<P, R>, ordered: Boolean = false, handler: suspend context(Raise<ServerError>) Call.(P) -> R) = route(method, RouteAccess.Linked, ordered, handler)

    /** A route a paired Device or an execution link may call. */
    fun <P, R> deviceOrLinked(method: Method<P, R>, ordered: Boolean = false, handler: suspend context(Raise<ServerError>) Call.(P) -> R) = route(method, RouteAccess.DeviceOrLinked, ordered, handler)

    internal fun build(): Routes = RouteTable(routes.toMap())
}

fun routes(build: RouteBuilder.() -> Unit): Routes = RouteBuilder().apply(build).build()
