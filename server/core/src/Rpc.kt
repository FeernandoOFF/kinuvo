package kinuvo.server

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
