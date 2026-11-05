package kinuvo.harness.server

import kinuvo.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException

internal const val MAX_PAGE = 200

/** Decodes [r]'s params for [method] and encodes what [block] returns; a failure becomes an error response. */
@OptIn(ExperimentalSerializationApi::class)
internal suspend fun <P, R> respond(r: Frame.Request, method: Method<P, R>, block: suspend (P) -> R): Frame.Response =
    try {
        val params = ProtocolJson.decodeFromJsonElement(method.params, r.params)
        Frame.Response(r.id, ok = true, payload = ProtocolJson.encodeToJsonElement(method.result, block(params)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Frame.Response(r.id, ok = false, error = if (method == Api.Connect && e is MissingFieldException && "protocolVersion" in e.missingFields) ProtocolVersion.Mismatch else e.message ?: e.toString())
    }

internal class RequestHandlers<C> {
    private val handlers = mutableMapOf<String, suspend (Frame.Request, C) -> Frame.Response>()

    fun <P, R> on(method: Method<P, R>, block: suspend C.(P) -> R) {
        method.requireDirection(MethodDirection.ToServer)
        require(method.name !in handlers) { "Duplicate handler: ${method.name}" }
        handlers[method.name] = { request, context -> respond(request, method) { context.block(it) } }
    }

    fun requireComplete(special: List<Method<*, *>> = emptyList()) {
        special.forEach { it.requireDirection(MethodDirection.ToServer) }
        val names = handlers.keys + special.map { it.name }
        require(names.size == handlers.size + special.size) { "Duplicate special handler" }
        val expected = Api.methods.filter { it.direction == MethodDirection.ToServer }.map { it.name }.toSet()
        require(names == expected) { "Handler mismatch: missing ${expected - names}, unexpected ${names - expected}" }
    }

    fun accepts(name: String): Boolean = name in handlers

    suspend fun handle(request: Frame.Request, context: C): Frame.Response =
        handlers[request.method]?.invoke(request, context)
            ?: Frame.Response(request.id, ok = false, error = "Unknown method ${request.method}")
}
