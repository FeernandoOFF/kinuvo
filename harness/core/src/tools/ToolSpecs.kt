package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.execution.api.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** [this] as the run's neutral spec; Koog stays on this side of the boundary. */
fun ToolDescriptor.toSpec() = ToolSpec(
    name = name,
    description = description,
    schema = (this as? RawSchemaToolDescriptor)?.schema ?: toSchema(),
    verbatim = this is RawSchemaToolDescriptor,
)

fun ToolSpec.toDescriptor(): ToolDescriptor {
    if (verbatim) return RawSchemaToolDescriptor(name, description, schema)
    val properties = (schema["properties"] as? JsonObject ?: JsonObject(emptyMap()))
    val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    val (requiredParameters, optionalParameters) = properties.parameters().partition { it.name in required }
    return ToolDescriptor(name, description, requiredParameters, optionalParameters)
}

private fun ToolDescriptor.toSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject((requiredParameters + optionalParameters).associate { it.name to it.toSchema() }))
    put("required", JsonArray(requiredParameters.map { JsonPrimitive(it.name) }))
}

private fun ToolParameterDescriptor.toSchema(): JsonObject = JsonObject(type.toSchema() + ("description" to JsonPrimitive(description)))

private fun ToolParameterType.toSchema(): JsonObject = when (this) {
    ToolParameterType.String -> JsonObject(mapOf("type" to JsonPrimitive("string")))
    ToolParameterType.Integer -> JsonObject(mapOf("type" to JsonPrimitive("integer")))
    ToolParameterType.Float -> JsonObject(mapOf("type" to JsonPrimitive("number")))
    ToolParameterType.Boolean -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))
    ToolParameterType.Null -> JsonObject(mapOf("type" to JsonPrimitive("null")))
    is ToolParameterType.Enum -> buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(entries.map { JsonPrimitive(it) }))
    }
    is ToolParameterType.List -> buildJsonObject {
        put("type", "array")
        put("items", itemsType.toSchema())
    }
    is ToolParameterType.Object -> buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.associate { it.name to it.toSchema() }))
        put("required", JsonArray(requiredProperties.map { JsonPrimitive(it) }))
        additionalPropertiesType?.let { put("additionalProperties", it.toSchema()) }
            ?: additionalProperties?.let { put("additionalProperties", it) }
    }
    is ToolParameterType.AnyOf -> buildJsonObject {
        put("anyOf", JsonArray(types.map { JsonObject(it.toSchema() + ("title" to JsonPrimitive(it.name))) }))
    }
}

private fun JsonObject.parameters() = map { (name, schema) -> schema.jsonObject.parameter(name) }

private fun JsonObject.parameter(name: String) =
    ToolParameterDescriptor(name, get("description")?.jsonPrimitive?.contentOrNull.orEmpty(), type())

private fun JsonObject.type(): ToolParameterType {
    this["anyOf"]?.let { variants ->
        return ToolParameterType.AnyOf(variants.jsonArray.map { it.jsonObject.parameter(it.jsonObject["title"]!!.jsonPrimitive.content) }.toTypedArray())
    }
    return when (val type = getValue("type").jsonPrimitive.content) {
        "string" -> this["enum"]?.let { ToolParameterType.Enum(it.jsonArray.map { e -> e.jsonPrimitive.content }.toTypedArray()) } ?: ToolParameterType.String
        "integer" -> ToolParameterType.Integer
        "number" -> ToolParameterType.Float
        "boolean" -> ToolParameterType.Boolean
        "null" -> ToolParameterType.Null
        "array" -> ToolParameterType.List(getValue("items").jsonObject.type())
        "object" -> {
            val extra = this["additionalProperties"]
            ToolParameterType.Object(
                (this["properties"] as? JsonObject ?: JsonObject(emptyMap())).parameters(),
                this["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                (extra as? JsonPrimitive)?.booleanOrNull,
                (extra as? JsonObject)?.type(),
            )
        }
        else -> error("Unsupported schema type '$type'")
    }
}
