package kinuvo.server.tools

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.requireString
import kinuvo.harness.Agent
import kinuvo.harness.AgentDoc
import kinuvo.harness.AgentDocs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Edits Memory (MEMORY.md) and User Notes (USER.md) as bullet entries. Changes refresh in personal context on the next turn. */
class MemoryTool(private val agent: Agent, private val docs: AgentDocs, private val memoryCap: Int = 4000, private val userCap: Int = 2000) : CoreTool {
    override val descriptor = toolSpec(
        name = ToolNames.MEMORY,
        description = "Persist concise, durable information across Sessions. target=memory for verified lessons useful across many tasks; " +
            "target=user for confirmed facts and preferences about the user. Keep task-specific procedures in Skills and detailed world records in Knowledge. " +
            "Make all changes to one target in one call. Operations are atomic; the character cap applies to the final result. " +
            "add appends an entry; replace/remove match an existing entry by a unique substring (old_text). " +
            "Changes are saved immediately and refreshed in personal context on the next turn, including in this Session.",
        requiredParameters = listOf(
            ToolParameter("target", "memory | user", ParameterType.Enum(arrayOf("memory", "user"))),
        ),
        optionalParameters = listOf(
            ToolParameter("operations", "All changes to this target, in order", ParameterType.List(ParameterType.Object(
                properties = listOf(
                    ToolParameter("action", "add | replace | remove", ParameterType.Enum(arrayOf("add", "replace", "remove"))),
                    ToolParameter("content", "Entry text for add/replace", ParameterType.String),
                    ToolParameter("old_text", "Unique substring of the entry to replace/remove", ParameterType.String),
                ),
                requiredProperties = listOf("action"),
            ))),
            ToolParameter("action", "Single operation, when operations is omitted", ParameterType.Enum(arrayOf("add", "replace", "remove"))),
            ToolParameter("content", "Entry text for add/replace", ParameterType.String),
            ToolParameter("old_text", "Unique substring of the entry to replace/remove", ParameterType.String),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val (doc, cap) = when (args.requireString("target")) {
            "memory" -> AgentDoc.Memory to memoryCap
            "user" -> AgentDoc.UserNotes to userCap
            else -> error("target must be memory or user")
        }
        val operations = args["operations"]?.jsonArray?.map { it.jsonObject } ?: listOf(args)
        require(operations.isNotEmpty()) { "operations must not be empty" }
        var count = 0
        val text = docs.update(agent.id, doc) { old ->
            val entries = old.lines().filter { it.isNotBlank() }.map { it.removePrefix("- ").trim() }.toMutableList()
            for (operation in operations) when (operation.requireString("action")) {
                "add" -> operation.requireString("content").oneLine().let { entry -> if (entries.none { it.equals(entry, ignoreCase = true) }) entries += entry }
                "replace" -> entries[indexOf(entries, operation.requireString("old_text"))] = operation.requireString("content").oneLine()
                "remove" -> entries.removeAt(indexOf(entries, operation.requireString("old_text")))
                else -> error("action must be add, replace or remove")
            }
            count = entries.size
            entries.joinToString("") { "- $it\n" }.also {
                require(it.length <= cap) { "Over the ${cap}-char limit (${it.length}). Consolidate or remove entries first." }
            }
        }
        return "OK. $count entries, ${text.length}/$cap chars."
    }

    private fun indexOf(entries: List<String>, needle: String): Int {
        val matches = entries.indices.filter { entries[it].contains(needle) }
        require(matches.size == 1) { if (matches.isEmpty()) "No entry contains '$needle'" else "'$needle' matches ${matches.size} entries; be more specific" }
        return matches.single()
    }

    private fun String.oneLine() = replace(Regex("\\s*\\n\\s*"), " ").trim()
}
