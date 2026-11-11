package kinuvo.server.plugin

import kinuvo.harness.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolParameter(val name: String, val description: String, val type: ParameterType)

/** A tool parameter's JSON Schema type. */
sealed interface ParameterType {
    data object String : ParameterType
    data object Integer : ParameterType
    data object Float : ParameterType
    data object Boolean : ParameterType
    data object Null : ParameterType
    class Enum(val entries: Array<kotlin.String>) : ParameterType
    class List(val itemsType: ParameterType) : ParameterType
    class Object(
        val properties: kotlin.collections.List<ToolParameter>,
        val requiredProperties: kotlin.collections.List<kotlin.String> = emptyList(),
        val additionalProperties: kotlin.Boolean? = null,
        val additionalPropertiesType: ParameterType? = null,
    ) : ParameterType
    class AnyOf(val types: Array<ToolParameter>) : ParameterType
}

/** A tool whose arguments are the typed [requiredParameters] and [optionalParameters], as the JSON Schema models are sent. */
fun toolSpec(
    name: String,
    description: String,
    requiredParameters: List<ToolParameter> = emptyList(),
    optionalParameters: List<ToolParameter> = emptyList(),
) = ToolSpec(name, description, buildJsonObject {
    put("type", "object")
    put("properties", JsonObject((requiredParameters + optionalParameters).associate { it.name to it.toSchema() }))
    put("required", JsonArray(requiredParameters.map { JsonPrimitive(it.name) }))
})

/** A tool whose JSON Schema is used as-is, e.g. an external MCP's. */
fun rawSchemaSpec(name: String, description: String, schema: JsonObject) = ToolSpec(name, description, schema, verbatim = true)

private fun ToolParameter.toSchema(): JsonObject = JsonObject(type.toSchema() + ("description" to JsonPrimitive(description)))

private fun ParameterType.toSchema(): JsonObject = when (this) {
    ParameterType.String -> JsonObject(mapOf("type" to JsonPrimitive("string")))
    ParameterType.Integer -> JsonObject(mapOf("type" to JsonPrimitive("integer")))
    ParameterType.Float -> JsonObject(mapOf("type" to JsonPrimitive("number")))
    ParameterType.Boolean -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))
    ParameterType.Null -> JsonObject(mapOf("type" to JsonPrimitive("null")))
    is ParameterType.Enum -> buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(entries.map { JsonPrimitive(it) }))
    }
    is ParameterType.List -> buildJsonObject {
        put("type", "array")
        put("items", itemsType.toSchema())
    }
    is ParameterType.Object -> buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.associate { it.name to it.toSchema() }))
        put("required", JsonArray(requiredProperties.map { JsonPrimitive(it) }))
        additionalPropertiesType?.let { put("additionalProperties", it.toSchema()) }
            ?: additionalProperties?.let { put("additionalProperties", it) }
    }
    is ParameterType.AnyOf -> buildJsonObject {
        put("anyOf", JsonArray(types.map { JsonObject(it.toSchema() + ("title" to JsonPrimitive(it.name))) }))
    }
}
