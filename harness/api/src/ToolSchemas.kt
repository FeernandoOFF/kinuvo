package kinuvo.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** [this] as sent to a model that takes schemas up to [level]: parameters beyond it are dropped, and null when a required one is. */
fun ToolSpec.simplified(level: SchemaLevel): ToolSpec? {
    if (level == SchemaLevel.Full) return this
    if (verbatim) return null
    val parameters = schemaProperties
    val required = parameters.keys.filter { it in requiredNames }
    if (!required.all { parameters.getValue(it).fits(level) }) return null
    val optional = parameters.keys.filter { it !in requiredNames && parameters.getValue(it).fits(level) }
    if (required.size + optional.size == parameters.size) return this
    return copy(schema = parameterSchema(parameters, required, optional))
}

val ToolSpec.schemaProperties: Map<String, JsonObject>
    get() = (schema["properties"] as? JsonObject).orEmpty().mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) }

val ToolSpec.requiredNames: Set<String>
    get() = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()

fun parameterSchema(properties: Map<String, JsonObject>, required: List<String>, optional: List<String>) = JsonObject(mapOf(
    "type" to JsonPrimitive("object"),
    "properties" to JsonObject((required + optional).associateWith { properties.getValue(it) }),
    "required" to JsonArray(required.map { JsonPrimitive(it) }),
))

private val JsonObject.type get() = (this["type"] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.fits(level: SchemaLevel): Boolean {
    if ("anyOf" in this) return false
    return when (type) {
        "string", "integer", "number", "boolean" -> true
        "array" -> (this["items"] as? JsonObject)?.let { it.type != "array" && it.fits(level) } == true
        "object" -> level == SchemaLevel.OneLevel && (this["properties"] as? JsonObject).orEmpty().values.all { (it as? JsonObject)?.fits(SchemaLevel.Flat) == true }
        else -> false
    }
}
