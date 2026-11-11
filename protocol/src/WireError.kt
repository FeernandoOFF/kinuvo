package kinuvo.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A failed request: a stable [code] clients branch on, a [message] for people and optional structured [data]. */
@Serializable
data class WireError(val code: String, val message: String, val data: JsonElement? = null)

/** The codes a Server sends. Clients treat any other code as a generic failure. */
object WireErrorCode {
    const val InvalidParams = "invalid_params"
    const val NotFound = "not_found"
    const val Forbidden = "forbidden"
    const val Conflict = "conflict"
    const val Unavailable = "unavailable"
    const val ProtocolMismatch = "protocol_mismatch"
    const val Internal = "internal"
}

/** Reads the plain string error of an older peer, so a protocol mismatch still reads as one. */
object WireErrorSerializer : JsonTransformingSerializer<WireError>(WireError.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return element
        return buildJsonObject {
            put("code", if (text.startsWith("${WireErrorCode.ProtocolMismatch}:")) WireErrorCode.ProtocolMismatch else WireErrorCode.Internal)
            put("message", text)
        }
    }
}
