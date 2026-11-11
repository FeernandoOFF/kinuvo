package kinuvo.client

import kinuvo.protocol.WireError
import kinuvo.protocol.WireErrorCode
import kotlinx.serialization.json.JsonElement

/** The words for a refusal that needs management access; the client words every `forbidden` this way. */
const val ManagementAccessMessage = "This action requires management access"

/** A request the Server answered with an error. [code] says what kind of failure it was; [message] is for people. */
sealed class KinuvoException(val code: String, message: String, val data: JsonElement? = null) : IllegalStateException(message) {
    class InvalidParams(message: String, data: JsonElement? = null) : KinuvoException(WireErrorCode.InvalidParams, message, data)
    class NotFound(message: String, data: JsonElement? = null) : KinuvoException(WireErrorCode.NotFound, message, data)
    class Forbidden(message: String = ManagementAccessMessage, data: JsonElement? = null) : KinuvoException(WireErrorCode.Forbidden, message, data)
    class Conflict(message: String, data: JsonElement? = null) : KinuvoException(WireErrorCode.Conflict, message, data)
    class Unavailable(message: String, data: JsonElement? = null) : KinuvoException(WireErrorCode.Unavailable, message, data)

    /** An internal failure, or a code this client doesn't know. */
    class Server(code: String, message: String, data: JsonElement? = null) : KinuvoException(code, message, data)
}

internal fun WireError.toException(): KinuvoException = when (code) {
    WireErrorCode.InvalidParams -> KinuvoException.InvalidParams(message, data)
    WireErrorCode.NotFound -> KinuvoException.NotFound(message, data)
    WireErrorCode.Forbidden -> KinuvoException.Forbidden(data = data)
    WireErrorCode.Conflict -> KinuvoException.Conflict(message, data)
    WireErrorCode.Unavailable -> KinuvoException.Unavailable(message, data)
    else -> KinuvoException.Server(code, message, data)
}
