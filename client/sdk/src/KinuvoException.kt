package kinuvo.client

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kinuvo.protocol.Method
import kinuvo.protocol.WireError
import kinuvo.protocol.WireErrorCode
import kinuvo.protocol.WireErrorReason
import kinuvo.protocol.reason
import kotlinx.serialization.json.JsonElement

/** The words for a refusal that needs management access; the client words a `forbidden` with the `management` reason this way. */
const val ManagementAccessMessage = "This action requires management access"

/** A request the Server answered with an error. [code] says what kind of failure it was; [message] is for people. */
sealed class KinuvoException(val code: String, message: String, val data: JsonElement? = null) : IllegalStateException(message) {
    class InvalidParams(message: String, data: JsonElement? = null) : KinuvoException(WireErrorCode.InvalidParams, message, data)
    class NotFound(message: String, data: JsonElement? = null) : KinuvoException(WireErrorCode.NotFound, message, data)
    /** A refusal; [management] when it needs management access, which [message] then words as [ManagementAccessMessage]. */
    class Forbidden(message: String, data: JsonElement? = null, val management: Boolean = false) : KinuvoException(WireErrorCode.Forbidden, message, data)
    class Conflict(message: String, data: JsonElement? = null) : KinuvoException(WireErrorCode.Conflict, message, data)
    class Unavailable(message: String, data: JsonElement? = null) : KinuvoException(WireErrorCode.Unavailable, message, data)

    /** An internal failure, or a code this client doesn't know. */
    class Server(code: String, message: String, data: JsonElement? = null) : KinuvoException(code, message, data)
}

internal fun WireError.toException(): KinuvoException = when (code) {
    WireErrorCode.InvalidParams -> KinuvoException.InvalidParams(message, data)
    WireErrorCode.NotFound -> KinuvoException.NotFound(message, data)
    WireErrorCode.Forbidden -> if (reason == WireErrorReason.Management) KinuvoException.Forbidden(ManagementAccessMessage, data, management = true) else KinuvoException.Forbidden(message, data)
    WireErrorCode.Conflict -> KinuvoException.Conflict(message, data)
    WireErrorCode.Unavailable -> KinuvoException.Unavailable(message, data)
    else -> KinuvoException.Server(code, message, data)
}

/** A failed request as a value: what [KinuvoException] throws, plus a connection that is down. */
sealed interface KinuvoError {
    val code: String
    val message: String
    val data: JsonElement? get() = null

    data class InvalidParams(override val message: String, override val data: JsonElement? = null) : KinuvoError { override val code get() = WireErrorCode.InvalidParams }
    data class NotFound(override val message: String, override val data: JsonElement? = null) : KinuvoError { override val code get() = WireErrorCode.NotFound }
    data class Forbidden(override val message: String, override val data: JsonElement? = null, val management: Boolean = false) : KinuvoError { override val code get() = WireErrorCode.Forbidden }
    data class Conflict(override val message: String, override val data: JsonElement? = null) : KinuvoError { override val code get() = WireErrorCode.Conflict }
    data class Unavailable(override val message: String, override val data: JsonElement? = null) : KinuvoError { override val code get() = WireErrorCode.Unavailable }

    /** The connection is down; the client reconnects on its own. */
    data class Disconnected(override val message: String) : KinuvoError { override val code get() = WireErrorCode.Unavailable }

    /** An internal failure, or a code this client doesn't know. */
    data class Server(override val code: String, override val message: String, override val data: JsonElement? = null) : KinuvoError
}

fun KinuvoException.toError(): KinuvoError = when (this) {
    is KinuvoException.InvalidParams -> KinuvoError.InvalidParams(message.orEmpty(), data)
    is KinuvoException.NotFound -> KinuvoError.NotFound(message.orEmpty(), data)
    is KinuvoException.Forbidden -> KinuvoError.Forbidden(message.orEmpty(), data, management)
    is KinuvoException.Conflict -> KinuvoError.Conflict(message.orEmpty(), data)
    is KinuvoException.Unavailable -> KinuvoError.Unavailable(message.orEmpty(), data)
    is KinuvoException.Server -> KinuvoError.Server(code, message.orEmpty(), data)
}

/** Like [KinuvoClient.call], but a Server error or a closed connection is a [KinuvoError] value instead of an exception. */
suspend fun <P, R> KinuvoClient.request(method: Method<P, R>, params: P): Either<KinuvoError, R> =
    try { call(method, params).right() }
    catch (e: KinuvoException) { e.toError().left() }
    catch (e: DisconnectedException) { KinuvoError.Disconnected(e.message.orEmpty()).left() }
