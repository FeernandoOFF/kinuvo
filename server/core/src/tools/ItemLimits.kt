package kinuvo.server.tools

import kinuvo.harness.parameterSchema
import kinuvo.harness.requiredNames
import kinuvo.harness.schemaProperties
import kinuvo.harness.ToolSpec
import kinuvo.server.plugin.Tool
import kotlinx.serialization.json.*

private val limitNames = setOf("limit", "max_results", "maxResults", "top_k", "page_size")

fun Tool.itemLimits(maxItems: Int?): Map<String, Int> {
    if (maxItems == null) return emptyMap()
    coreMetadata?.let { entry -> return entry.itemLimit?.takeUnless { it.lines }?.let { mapOf(it.parameter to minOf(it.max, maxItems)) }.orEmpty() }
    if (descriptor.verbatim) return (descriptor.schema["properties"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
        val property = value as? JsonObject ?: return@mapNotNull null
        if (name !in limitNames || property["type"]?.jsonPrimitive?.content != "integer") null
        else name to minOf(maxItems, property["maximum"]?.jsonPrimitive?.intOrNull ?: maxItems)
    }.toMap()
    return descriptor.schemaProperties.filter { (name, property) -> name in limitNames && property["type"]?.jsonPrimitive?.contentOrNull == "integer" }
        .mapValues { maxItems }
}

fun ToolSpec.withItemLimits(limits: Map<String, Int>): ToolSpec {
    if (limits.isEmpty()) return this
    fun description(old: String, max: Int) = old.replace(Regex("""\(default \d+[^)]*\)"""), "").trim() + " (default $max, max $max for this model)"
    if (verbatim) {
        val properties = schema["properties"] as? JsonObject ?: return this
        return copy(schema = JsonObject(schema + ("properties" to JsonObject(properties.mapValues { (name, value) ->
            val max = limits[name]
            if (max == null || value !is JsonObject) value else JsonObject(value + mapOf(
                "description" to JsonPrimitive(description(value["description"]?.jsonPrimitive?.content.orEmpty(), max)),
                "default" to JsonPrimitive(max), "maximum" to JsonPrimitive(max)))
        }))))
    }
    val properties = schemaProperties
    val required = properties.keys.filter { it in requiredNames }
    val optional = properties.keys.filter { it !in requiredNames }
    val limited = properties.mapValues { (name, property) ->
        limits[name]?.let { max -> JsonObject(property + ("description" to JsonPrimitive(description(property["description"]?.jsonPrimitive?.contentOrNull.orEmpty(), max)))) } ?: property
    }
    val missing = limits.filterKeys { it !in properties }.mapValues { (_, max) ->
        JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Result count (default $max, max $max)")))
    }
    return copy(schema = parameterSchema(limited + missing, required, optional + missing.keys))
}

internal fun JsonObject.clampItems(limits: Map<String, Int>): JsonObject = JsonObject(this + limits.mapValues { (name, max) ->
    JsonPrimitive(((get(name) as? JsonPrimitive)?.intOrNull ?: max).coerceIn(1, max.coerceAtLeast(1)))
})
