package kinuvo.server.tools

import kinuvo.harness.requiredNames
import kinuvo.harness.schemaProperties
import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.protocol.ToolNames
import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.ToolRun
import kinuvo.server.plugin.requireString
import kinuvo.server.plugin.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Past [LIMIT] tools, the catalogued ones (e.g. MCP servers') aren't each sent to the model: it finds, describes and
 * calls them through [tools], or from a Script. Each call still goes through its own tool's Approval.
 */
object ToolCatalog {
    const val LIMIT = 40
    /** On tiers that always catalogue external tools, this many or fewer are still sent directly. */
    const val DIRECT_LIMIT = 8

    fun tools(catalogued: List<Tool>, maxItems: Int? = null): List<Tool> {
        val limited = catalogued.map { tool -> object : Tool by tool {
            override val descriptor = tool.descriptor.withItemLimits(tool.itemLimits(maxItems))
        } }
        return listOf(FindTools(limited), DescribeTool(limited), CallTool(limited))
    }
}

private fun List<Tool>.named(name: String) = requireNotNull(firstOrNull { it.descriptor.name == name }) { "No tool '$name' in the catalog; search it first" }

private class FindTools(private val catalogued: List<Tool>) : CoreTool {
    override val catalogTargets get() = catalogued
    private val sources = catalogued.mapNotNull { it.identity?.instance?.instanceId?.substringAfterLast('/') }.distinct()
    override val descriptor = toolSpec(
        ToolNames.FIND_TOOLS,
        "Search ${catalogued.size} more tools (from ${sources.joinToString()}) by what you need; returns names and descriptions. " +
            "Read one's parameters with the catalog's describe tool, then run it with its call tool or as `tools.<name>` in a Script.",
        requiredParameters = listOf(ToolParameter("query", "Words describing the tool, e.g. \"create issue\"; empty lists them all", ParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val words = args.string("query").orEmpty().lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        val found = catalogued.map { t -> t to "${t.descriptor.name} ${t.descriptor.description}".lowercase().let { text -> words.count(text::contains) } }
            .filter { words.isEmpty() || it.second > 0 }.sortedByDescending { it.second }.take((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, MAX) ?: MAX).map { it.first }
        return found.joinToString("\n") { "- ${it.descriptor.name}: ${it.descriptor.description.replace('\n', ' ').take(160)}" }
            .ifEmpty { "No tools match; try other words or an empty query." }
    }

    companion object { const val MAX = 25 }
}

private class DescribeTool(private val catalogued: List<Tool>) : CoreTool {
    override val catalogTargets get() = catalogued
    override val descriptor = toolSpec(
        ToolNames.DESCRIBE_TOOL, "A catalogued tool's description and parameters (JSON Schema).",
        requiredParameters = listOf(ToolParameter("name", "The tool's exact name from the catalog search", ParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val d = catalogued.named(args.requireString("name")).descriptor
        return buildJsonObject {
            put("name", d.name)
            put("description", d.description)
            if (d.verbatim) put("parameters", d.schema) else putJsonObject("parameters") {
                val properties = d.schemaProperties
                val required = properties.keys.filter { it in d.requiredNames }
                putJsonObject("properties") {
                    (required + properties.keys.filter { it !in d.requiredNames }).forEach { name ->
                        val property = properties.getValue(name)
                        putJsonObject(name) { put("type", property.typeName()); put("description", property["description"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
                    }
                }
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }.toString()
    }
}

private class CallTool(private val catalogued: List<Tool>) : CoreTool {
    override val catalogTargets get() = catalogued
    override val descriptor = toolSpec(
        ToolNames.CALL_TOOL, "Run a catalogued tool; it asks for Approval like the tool itself would.",
        requiredParameters = listOf(
            ToolParameter("name", "The tool's exact name", ParameterType.String),
            ToolParameter("arguments", "Its arguments as a JSON object string, following its described parameters", ParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String = error("A catalogued tool needs a run to be called in")

    override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
        val name = catalogued.named(args.requireString("name")).descriptor.name
        val result = run.call(name, args.string("arguments")?.ifBlank { null } ?: "{}")
        check(!result.isError) { result.output }
        return result.output
    }
}

private fun JsonObject.typeName(): String {
    if ("anyOf" in this) return "anyof"
    return when (val type = (this["type"] as? JsonPrimitive)?.contentOrNull) {
        "string" -> if ("enum" in this) "enum" else "string"
        "number" -> "float"
        "array" -> "list"
        else -> type.orEmpty()
    }
}
