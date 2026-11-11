package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.either
import kinuvo.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException

const val MAX_PAGE = 200

/** Decodes [r]'s params for [method] and encodes what [block] returns; a raised [ServerError] or a failure becomes an error response. */
@OptIn(ExperimentalSerializationApi::class)
suspend fun <P, R> respond(r: Frame.Request, method: Method<P, R>, block: suspend context(Raise<ServerError>) (P) -> R): Frame.Response =
    try {
        val params = ProtocolJson.decodeFromJsonElement(method.params, r.params)
        either { block(params) }.fold(
            { r.failure(it) },
            { Frame.Response(r.id, ok = true, payload = ProtocolJson.encodeToJsonElement(method.result, it)) },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val mismatch = method == Api.Connect && e is MissingFieldException && "protocolVersion" in e.missingFields
        Frame.Response(r.id, ok = false, error = if (mismatch) ConnectError.ProtocolMismatch.toWireError() else e.toWireError())
    }
