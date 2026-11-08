package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.*

private val limitNames = setOf("limit", "max_results", "maxResults", "top_k", "page_size")

fun Tool.itemLimits(maxItems: Int?): Map<String, Int> {
    if (maxItems == null) return emptyMap()
    coreMetadata?.let { entry -> return entry.itemLimit?.takeUnless { it.lines }?.let { mapOf(it.parameter to minOf(it.max, maxItems)) }.orEmpty() }
    val raw = descriptor as? RawSchemaToolDescriptor
    if (raw != null) return (raw.schema["properties"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
        val property = value as? JsonObject ?: return@mapNotNull null
        if (name !in limitNames || property["type"]?.jsonPrimitive?.content != "integer") null
        else name to minOf(maxItems, property["maximum"]?.jsonPrimitive?.intOrNull ?: maxItems)
    }.toMap()
    return (descriptor.requiredParameters + descriptor.optionalParameters).filter { it.name in limitNames && it.type == ToolParameterType.Integer }
        .associate { it.name to maxItems }
}

fun ToolDescriptor.withItemLimits(limits: Map<String, Int>): ToolDescriptor {
    if (limits.isEmpty()) return this
    fun description(old: String, max: Int) = old.replace(Regex("""\(default \d+[^)]*\)"""), "").trim() + " (default $max, max $max for this model)"
    if (this is RawSchemaToolDescriptor) {
        val properties = schema["properties"] as? JsonObject ?: return this
        return RawSchemaToolDescriptor(name, this.description, JsonObject(schema + ("properties" to JsonObject(properties.mapValues { (name, value) ->
            val max = limits[name]
            if (max == null || value !is JsonObject) value else JsonObject(value + mapOf(
                "description" to JsonPrimitive(description(value["description"]?.jsonPrimitive?.content.orEmpty(), max)),
                "default" to JsonPrimitive(max), "maximum" to JsonPrimitive(max)))
        }))))
    }
    fun parameter(p: ToolParameterDescriptor) = limits[p.name]?.let { ToolParameterDescriptor(p.name, description(p.description, it), p.type) } ?: p
    val missing = limits.filterKeys { key -> (requiredParameters + optionalParameters).none { it.name == key } }
        .map { (name, max) -> ToolParameterDescriptor(name, "Result count (default $max, max $max)", ToolParameterType.Integer) }
    return ToolDescriptor(name, this.description, requiredParameters.map(::parameter), optionalParameters.map(::parameter) + missing)
}

internal fun JsonObject.clampItems(limits: Map<String, Int>): JsonObject = JsonObject(this + limits.mapValues { (name, max) ->
    JsonPrimitive(((get(name) as? JsonPrimitive)?.intOrNull ?: max).coerceIn(1, max.coerceAtLeast(1)))
})
