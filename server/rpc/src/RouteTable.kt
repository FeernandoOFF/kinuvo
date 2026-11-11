package kinuvo.server.rpc

import kinuvo.protocol.Frame
import kinuvo.protocol.Method
import kinuvo.server.Call
import kinuvo.server.RouteAccess
import kinuvo.server.Routes
import kinuvo.server.respond

internal class Route(val access: RouteAccess, val handle: suspend (Call, Frame.Request) -> Frame.Response)

private class RouteTable(private val routes: Map<String, Route>) : Routes {
    override val methods get() = routes.keys

    override fun access(method: String) = routes[method]?.access

    override suspend fun dispatch(call: Call, request: Frame.Request) =
        routes[request.method]?.handle?.invoke(call, request) ?: Frame.Response(request.id, ok = false, error = "Unknown method ${request.method}")
}

/** Registers one handler per [Method]; a method registered twice fails the build. */
class RouteBuilder internal constructor() {
    private val routes = LinkedHashMap<String, Route>()

    fun <P, R> route(method: Method<P, R>, access: RouteAccess, handler: suspend Call.(P) -> R) {
        require(routes.put(method.name, Route(access) { call, request -> respond(request, method) { call.handler(it) } }) == null) { "Duplicate route for ${method.name}" }
    }

    /** A route that needs management access. */
    fun <P, R> handle(method: Method<P, R>, handler: suspend Call.(P) -> R) = route(method, RouteAccess.Management, handler)

    /** A route any paired Device may call. */
    fun <P, R> device(method: Method<P, R>, handler: suspend Call.(P) -> R) = route(method, RouteAccess.Device, handler)

    /** A route only another Server's execution link may call. */
    fun <P, R> linked(method: Method<P, R>, handler: suspend Call.(P) -> R) = route(method, RouteAccess.Linked, handler)

    /** A route a paired Device or an execution link may call. */
    fun <P, R> deviceOrLinked(method: Method<P, R>, handler: suspend Call.(P) -> R) = route(method, RouteAccess.DeviceOrLinked, handler)

    internal fun build(): Routes = RouteTable(routes.toMap())
}

fun routes(build: RouteBuilder.() -> Unit): Routes = RouteBuilder().apply(build).build()
