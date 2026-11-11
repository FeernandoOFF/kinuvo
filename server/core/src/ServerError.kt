package kinuvo.server

import kinuvo.protocol.Frame
import kinuvo.protocol.ProtocolVersion
import kinuvo.protocol.WireError
import kinuvo.protocol.WireErrorCode
import kotlinx.serialization.json.JsonElement

/** The shown text of a refusal that needs management access. */
const val MANAGEMENT_REQUIRED = "This action requires management access"

/** An expected failure of a request, sent to the client with its [code]. Any other exception is an invalid request or an internal failure. */
sealed class ServerError(val code: String, message: String, val data: JsonElement? = null) : IllegalStateException(message) {
    class NotFound(message: String, data: JsonElement? = null) : ServerError(WireErrorCode.NotFound, message, data)
    class Forbidden(message: String = MANAGEMENT_REQUIRED, data: JsonElement? = null) : ServerError(WireErrorCode.Forbidden, message, data)
    class Conflict(message: String, data: JsonElement? = null) : ServerError(WireErrorCode.Conflict, message, data)
    class Unavailable(message: String, data: JsonElement? = null) : ServerError(WireErrorCode.Unavailable, message, data)
    class ProtocolMismatch : ServerError(WireErrorCode.ProtocolMismatch, ProtocolVersion.Mismatch)
}

/** Bad params (including ones that fail to decode) are `invalid_params`; anything unexpected is `internal`. */
fun Throwable.toWireError(): WireError = when (this) {
    is ServerError -> WireError(code, message.orEmpty(), data)
    is IllegalArgumentException -> WireError(WireErrorCode.InvalidParams, message ?: toString())
    else -> WireError(WireErrorCode.Internal, message ?: toString())
}

fun Frame.Request.failure(error: ServerError) = Frame.Response(id, ok = false, error = error.toWireError())
