package kinuvo.harness

import kinuvo.protocol.*
import kotlinx.serialization.json.*

fun toolEntries(message: ExecutionMessage): List<SessionEntry> {
    val payload = message.payload as? JsonObject
    val native = payload?.takeIf { "type" in it }?.let {
        runCatching { ProtocolJson.decodeFromJsonElement(SessionEntry.serializer(), it) }.getOrNull()
    }
    if (native is SessionEntry.ToolCall || native is SessionEntry.ToolResult) return listOf(native)
    val id = (payload?.get("toolCallId") as? JsonPrimitive)?.content ?: message.id
    val input = payload?.get("rawInput")?.toString() ?: ""
    val call = SessionEntry.ToolCall(id, message.text, input)
    val status = (payload?.get("status") as? JsonPrimitive)?.content
    if (status != "completed" && status != "failed") return listOf(call)
    val output = payload?.get("rawOutput")?.takeUnless { it is JsonNull } ?: payload?.get("content")?.takeUnless { it is JsonNull }
    return listOf(call, SessionEntry.ToolResult(id, message.text, output?.let { (it as? JsonPrimitive)?.content ?: it.toString() } ?: "", isError = status == "failed"))
}

fun transcript(snapshot: ExecutionSnapshot): List<SessionEntry> = snapshot.messages.flatMap { m ->
    val native = m.payload?.let { runCatching { ProtocolJson.decodeFromJsonElement(SessionEntry.serializer(), it) }.getOrNull() }
    when {
        native is SessionEntry.UserMessage -> listOf(native)
        native is SessionEntry.AssistantMessage -> listOf(native.copy(messageId = native.messageId ?: m.id))
        native is SessionEntry.Reasoning -> listOf(native.copy(messageId = native.messageId ?: m.id))
        native is SessionEntry.VoiceCall -> listOf(native)
        native is SessionEntry.Notice -> listOf(native)
        m.role == "user" -> listOf(SessionEntry.UserMessage(m.text))
        m.role == "tool" -> toolEntries(m)
        m.role == "reasoning" -> listOf(SessionEntry.Reasoning(m.text, messageId = m.id))
        else -> listOf(SessionEntry.AssistantMessage(m.text, m.id))
    }
}
