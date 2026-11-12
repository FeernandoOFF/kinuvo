package kinuvo.protocol.surface

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kinuvo.protocol.session.ToolCallId

/** An A2UI v1.0 catalog a Device can render, sent inline through surfaces.catalogs after authentication; mirrors the spec's catalog document. */
@Serializable
data class UiCatalog(
    val catalogId: String,
    val protocolVersion: String? = null,
    val title: String? = null,
    val description: String? = null,
    val instructions: String? = null,
    /** JSON Schemas by component name. */
    val components: Map<String, JsonObject> = emptyMap(),
    val functions: Map<String, JsonObject> = emptyMap(),
    @SerialName("\$schema") val schema: String? = null,
    @SerialName("\$id") val id: String? = null,
    @SerialName("\$defs") val defs: JsonObject? = null,
)

const val UI_PROTOCOL_VERSION = "v1.0"

/** The A2UI messages in a `show_ui` call's [args]; the protocol version is filled in where a message leaves it out. */
fun uiMessages(args: JsonObject): List<JsonObject> = (args["messages"] as? JsonArray ?: error("Give 'messages' as an array")).map { m ->
    val message = m as? JsonObject ?: error("Each message must be an object")
    if ("version" in message) message else JsonObject(mapOf("version" to JsonPrimitive(UI_PROTOCOL_VERSION)) + message)
}

@Serializable
data class SurfaceAction(
    val callId: ToolCallId,
    val surfaceId: String,
    val name: String,
    val sourceComponentId: String,
    val context: JsonObject,
    val dataModel: JsonObject,
)

fun SurfaceAction.modelText(text: String): String =
    "$text\n\n[Surface action: $name; surface: $surfaceId; component: $sourceComponentId; call: $callId]\nContext: $context"

@Serializable
data class SurfaceImageParams(val url: String)

@Serializable
data class SurfaceImage(val mimeType: String, val base64: String)
