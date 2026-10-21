package kinuvo.harness.core.tools

import kinuvo.protocol.Risk

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.AgentDocs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Edits Memory (MEMORY.md) and User Notes (USER.md) as bullet entries. Changes refresh in personal context on the next turn. */
class MemoryTool(private val agent: Agent, private val docs: AgentDocs, private val memoryCap: Int = 4000, private val userCap: Int = 2000) : Tool {
    override val risk = Risk.Low

    override val descriptor = ToolDescriptor(
        name = ToolNames.MEMORY,
        description = "Persist concise, durable information across Sessions. target=memory for verified lessons useful across many tasks; " +
            "target=user for confirmed facts and preferences about the user. Keep task-specific procedures in Skills and detailed world records in Knowledge. " +
            "Make all changes to one target in one call. Operations are atomic; the character cap applies to the final result. " +
            "add appends an entry; replace/remove match an existing entry by a unique substring (old_text). " +
            "Changes are saved immediately and refreshed in personal context on the next turn, including in this Session.",
        requiredParameters = listOf(
            ToolParameterDescriptor("target", "memory | user", ToolParameterType.Enum(arrayOf("memory", "user"))),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("operations", "All changes to this target, in order", ToolParameterType.List(ToolParameterType.Object(
                properties = listOf(
                    ToolParameterDescriptor("action", "add | replace | remove", ToolParameterType.Enum(arrayOf("add", "replace", "remove"))),
                    ToolParameterDescriptor("content", "Entry text for add/replace", ToolParameterType.String),
                    ToolParameterDescriptor("old_text", "Unique substring of the entry to replace/remove", ToolParameterType.String),
                ),
                requiredProperties = listOf("action"),
            ))),
            ToolParameterDescriptor("action", "Single operation, when operations is omitted", ToolParameterType.Enum(arrayOf("add", "replace", "remove"))),
            ToolParameterDescriptor("content", "Entry text for add/replace", ToolParameterType.String),
            ToolParameterDescriptor("old_text", "Unique substring of the entry to replace/remove", ToolParameterType.String),
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
                "add" -> entries += operation.requireString("content").oneLine()
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
