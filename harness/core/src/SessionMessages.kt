package kinuvo.harness.core

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.dsl.RequestMessagePartsBuilder
import ai.koog.prompt.message.*
import kinuvo.protocol.Attachment
import kinuvo.protocol.SessionEntry

/** Session conversion belongs to the persistence feature, including files shown after tool results. */
class SessionMessages(private val model: AgentModel) {
    private val entries = mutableMapOf<Message, List<SessionEntry>>()

    fun prompt(instructions: String, history: List<SessionEntry>): Prompt {
        val initial = prompt("kinuvo", model.parameters(instructions)) {
            if (model.instructionsInPrompt && instructions.isNotBlank()) system(instructions)
        }
        val system = initial.messages.map { (it as Message.System).copy(metaInfo = RequestMetaInfo.Empty) }
        system.forEach { entries[it] = emptyList() }
        return initial.withMessages { system + messages(history) }
    }

    fun messages(history: List<SessionEntry>): List<Message> = history.flatMap { entry ->
        val block = prompt("session") {
            when (entry) {
                is SessionEntry.Context -> system(entry.content)
                is SessionEntry.UserMessage -> user { text(entry.modelText); attach(entry.attachments) }
                is SessionEntry.AssistantMessage -> assistant(entry.text)
                is SessionEntry.ToolCall -> toolCall(MessagePart.Tool.Call(entry.id, entry.name, entry.args))
                is SessionEntry.ToolResult -> toolResult(MessagePart.Tool.Result(entry.id, entry.name, entry.output, entry.isError))
                is SessionEntry.Reasoning -> if (entry.encrypted != null) reasoning(
                    MessagePart.Reasoning(content = emptyList(), summary = listOf(entry.text), encrypted = entry.encrypted, id = entry.id)
                )
                is SessionEntry.Compaction -> {
                    system("Summary of the earlier part of this conversation:\n\n${entry.summary}")
                    entry.activations.forEach { system(activation(it)) }
                }
                is SessionEntry.Activation -> system(activation(entry))
                else -> Unit
            }
        }.messages.map { message ->
            val normalized = when (message) {
                is Message.System -> message.copy(metaInfo = RequestMetaInfo.Empty)
                is Message.User -> message.copy(metaInfo = RequestMetaInfo.Empty)
                is Message.Assistant -> message.copy(metaInfo = ResponseMetaInfo.Empty)
            }
            entries[normalized] = listOf(if (entry is SessionEntry.ToolResult) entry.copy(attachments = emptyList()) else entry)
            normalized
        }
        if (entry is SessionEntry.ToolResult && entry.attachments.isNotEmpty())
            block + messages(listOf(SessionEntry.UserMessage("Files from the tool results above:", attachments = entry.attachments)))
        else block
    }

    fun entries(messages: List<Message>): List<SessionEntry> = messages.flatMap { entries[it] ?: when (it) {
        is Message.System -> listOf(SessionEntry.Context(it.textContent()))
        is Message.User -> listOf(SessionEntry.UserMessage(it.textContent()))
        is Message.Assistant -> assistantEntries(it)
    } }

    fun user(message: Message): Boolean = entries[message]?.any { it is SessionEntry.UserMessage && it.text != "Files from the tool results above:" } == true
    fun files(message: Message): Boolean = message.parts.any { it is MessagePart.Attachment }

    private fun RequestMessagePartsBuilder.attach(list: List<Attachment>) = list.forEach { a ->
        val bytes = requireNotNull(model.attachments) { "This provider has no attachment storage" }(a)
        val content = AttachmentContent.Binary.Bytes(bytes)
        if (a.mimeType.startsWith("image/")) image(AttachmentSource.Image(content, a.mimeType.substringAfter('/'), a.mimeType, a.name))
        else file(AttachmentSource.File(content = content, format = a.name.substringAfterLast('.'), mimeType = a.mimeType, fileName = a.name))
    }
}

internal fun assistantEntries(message: Message.Assistant, messageId: String? = null): List<SessionEntry> = message.parts.mapNotNull { part -> when (part) {
    is MessagePart.Text -> part.text.takeIf { it.isNotEmpty() }?.let { SessionEntry.AssistantMessage(it, messageId) }
    is MessagePart.Reasoning -> SessionEntry.Reasoning((part.summary?.takeIf { it.isNotEmpty() } ?: part.content).joinToString("\n\n"), part.id, part.encrypted, messageId)
    is MessagePart.Tool.Call -> SessionEntry.ToolCall(requireNotNull(part.id) { "The model returned a tool call without an ID" }, part.tool, part.args)
    else -> null
} }

private fun activation(a: SessionEntry.Activation) = "Skill '${a.name}' is active in this conversation:\n\n${a.content}"
