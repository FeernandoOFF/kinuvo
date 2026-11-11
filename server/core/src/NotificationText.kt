package kinuvo.server

import kinuvo.protocol.PendingInteraction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An Approval's Notification title and body: the command for shell calls, else the tool and its reason or main argument, never raw JSON. */
internal fun approvalText(agent: String, event: PendingInteraction.Approval): Pair<String, String> {
    val tool = event.identity?.localId ?: event.toolCallName
    val args = runCatching { Json.parseToJsonElement(event.args) as? JsonObject }.getOrNull()
    val strings = args?.values?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.ifEmpty { null } }.orEmpty()
    val command = (args?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.content?.lineSequence()?.firstOrNull()?.trim()
    if (event.identity?.instance?.pluginId == "kinuvo.exec" && tool == "shell" && command != null)
        return "$agent wants to run a command" to listOfNotNull(command, event.reason).joinToString("\n")
    val detail = event.reason ?: strings.firstOrNull()?.lineSequence()?.firstOrNull()
    return "$agent needs an Approval" to ("Allow ${tool.replace('_', ' ')}" + (detail?.let { ": $it" } ?: "?"))
}

/** [markdown] as plain text for a Notification: no code fences, emphasis, headings or link targets. */
internal fun plainText(markdown: String): String = markdown
    .replace(Regex("```[^\\n]*\\n?"), "")
    .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+])\\s+"), "")
    .replace(Regex("(\\*\\*|__|\\*|`)(\\S(?:.*?\\S)?)\\1"), "$2")
    .replace(Regex("\\n{2,}"), "\n")
    .trim()
