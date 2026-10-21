package kinuvo.harness.core

import kinuvo.protocol.SessionEntry
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import ai.koog.agents.core.tools.ToolDescriptor

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The latest compressed history followed by the Session's subsequent model entries. */
fun List<SessionEntry>.activeHistory(): List<SessionEntry> {
    val at = indexOfLast { it is SessionEntry.Compaction }
    val history = if (at < 0) this else (this[at] as SessionEntry.Compaction).history + drop(at + 1)
    return history.filterNot { it is SessionEntry.Started || it is SessionEntry.Settings || it is SessionEntry.Details || it is SessionEntry.Reflection || it is SessionEntry.ReflectionReview || it is SessionEntry.Measurement }
}

/** Screenshots a Computer tool shows the model are named so, and only the latest few are sent again. */
const val SCREENSHOT_PREFIX = "screen-"

/** Drops all but the last [keep] tool results' screenshots, which are many and soon outdated, noting where one was. */
fun List<SessionEntry>.recentScreens(keep: Int = 3): List<SessionEntry> {
    var seen = 0
    return asReversed().map { entry ->
        val attachments = when (entry) {
            is SessionEntry.ToolResult -> entry.attachments
            is SessionEntry.UserMessage -> entry.attachments.takeIf { entry.text.startsWith(TOOL_FILES) }.orEmpty()
            else -> emptyList()
        }
        if (attachments.none { it.name.startsWith(SCREENSHOT_PREFIX) } || ++seen <= keep) entry
        else {
            val kept = attachments.filterNot { it.name.startsWith(SCREENSHOT_PREFIX) }
            when (entry) {
                is SessionEntry.ToolResult -> entry.copy(attachments = kept, output = entry.output + "\n(Its screenshot is no longer shown.)")
                is SessionEntry.UserMessage -> entry.copy(attachments = kept, text = entry.text + "\n(Its screenshot is no longer shown.)")
                else -> entry
            }
        }
    }.asReversed()
}

/** Rough token estimate (~4 chars per token) of what would be sent to the model. */
fun List<SessionEntry>.estimatedTokens(instructions: String, tools: List<ToolDescriptor> = emptyList()): Int =
    (instructions.length + tools.sumOf { it.toString().length + if (it is RawSchemaToolDescriptor) it.schema.toString().length else 0 } + sumOf {
        when (it) {
            is SessionEntry.Context -> it.content.length
            is SessionEntry.UserMessage -> it.text.length + it.attachments.estimatedChars()
            is SessionEntry.AssistantMessage -> it.text.length
            is SessionEntry.Reasoning -> it.text.length + (it.encrypted?.length ?: 0)
            is SessionEntry.ToolCall -> it.name.length + it.args.length
            is SessionEntry.ToolResult -> it.output.length + it.attachments.estimatedChars()
            is SessionEntry.Compaction -> it.history.estimatedTokens("") * 4
            is SessionEntry.Activation -> it.content.length
            is SessionEntry.Started, is SessionEntry.Settings, is SessionEntry.Details, is SessionEntry.Reflection, is SessionEntry.ReflectionReview, is SessionEntry.Measurement -> 0
        }
    }) / 4

private fun List<kinuvo.protocol.Attachment>.estimatedChars() = sumOf { a -> if (a.mimeType.startsWith("image/")) 6400 else a.size }

/** Skills activated in [this] history (user activations, activate_skill results, earlier compactions), latest per name. */
fun List<SessionEntry>.activations(): List<SessionEntry.Activation> {
    val calls = filterIsInstance<SessionEntry.ToolCall>().filter { it.identity?.instance?.pluginId == "kinuvo.core" && it.identity?.localId == ToolNames.ACTIVATE_SKILL }.associateBy { it.id }
    val found = linkedMapOf<String, SessionEntry.Activation>()
    for (e in this) when (e) {
        is SessionEntry.Compaction -> e.history.activations().forEach { found[it.name] = it }
        is SessionEntry.Activation -> found[e.name] = e
        is SessionEntry.ToolResult -> if (!e.isError) calls[e.id]?.let { call ->
            val name = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(call.args).jsonObject["name"]?.jsonPrimitive?.content
            }.getOrNull()
            if (name != null) found[name] = SessionEntry.Activation(name, e.output)
        }
        else -> Unit
    }
    return found.values.toList()
}
