package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.protocol.Risk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Past [LIMIT] tools, the catalogued ones (e.g. MCP servers') aren't each sent to the model: it finds, describes and
 * calls them through [tools], or from a Script. Each call still goes through its own tool's Approval.
 */
object ToolCatalog {
    const val LIMIT = 40

    fun tools(catalogued: List<Tool>): List<Tool> = listOf(FindTools(catalogued), DescribeTool(catalogued), CallTool(catalogued))
}

private fun List<Tool>.named(name: String) = requireNotNull(firstOrNull { it.descriptor.name == name }) { "No tool '$name' in the catalog; search it first" }

private class FindTools(private val catalogued: List<Tool>) : Tool {
    override val risk = Risk.Low
    private val sources = catalogued.mapNotNull { it.identity?.instance?.instanceId?.substringAfterLast('/') }.distinct()
    override val descriptor = ToolDescriptor(
        ToolNames.FIND_TOOLS,
        "Search ${catalogued.size} more tools (from ${sources.joinToString()}) by what you need; returns names and descriptions. " +
            "Read one's parameters with the catalog's describe tool, then run it with its call tool or as `tools.<name>` in a Script.",
        requiredParameters = listOf(ToolParameterDescriptor("query", "Words describing the tool, e.g. \"create issue\"; empty lists them all", ToolParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val words = args.string("query").orEmpty().lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        val found = catalogued.map { t -> t to "${t.descriptor.name} ${t.descriptor.description}".lowercase().let { text -> words.count(text::contains) } }
            .filter { words.isEmpty() || it.second > 0 }.sortedByDescending { it.second }.take(MAX).map { it.first }
        return found.joinToString("\n") { "- ${it.descriptor.name}: ${it.descriptor.description.replace('\n', ' ').take(160)}" }
            .ifEmpty { "No tools match; try other words or an empty query." }
    }

    companion object { const val MAX = 25 }
}

private class DescribeTool(private val catalogued: List<Tool>) : Tool {
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        ToolNames.DESCRIBE_TOOL, "A catalogued tool's description and parameters (JSON Schema).",
        requiredParameters = listOf(ToolParameterDescriptor("name", "The tool's exact name from the catalog search", ToolParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val d = catalogued.named(args.requireString("name")).descriptor
        return buildJsonObject {
            put("name", d.name)
            put("description", d.description)
            if (d is RawSchemaToolDescriptor) put("parameters", d.schema) else putJsonObject("parameters") {
                putJsonObject("properties") {
                    (d.requiredParameters + d.optionalParameters).forEach { p ->
                        putJsonObject(p.name) { put("type", p.type.name.lowercase()); put("description", p.description) }
                    }
                }
                put("required", buildJsonArray { d.requiredParameters.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } })
            }
        }.toString()
    }
}

private class CallTool(private val catalogued: List<Tool>) : Tool {
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        ToolNames.CALL_TOOL, "Run a catalogued tool; it asks for Approval like the tool itself would.",
        requiredParameters = listOf(
            ToolParameterDescriptor("name", "The tool's exact name", ToolParameterType.String),
            ToolParameterDescriptor("arguments", "Its arguments as a JSON object string, following its described parameters", ToolParameterType.String),
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
