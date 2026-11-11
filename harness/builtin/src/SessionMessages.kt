package kinuvo.harness.builtin

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.dsl.RequestMessagePartsBuilder
import ai.koog.prompt.message.*
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.session.SessionEntry
import kotlin.uuid.Uuid
import kinuvo.harness.ResultLimits
import kinuvo.harness.modelText

/** Session conversion belongs to the persistence feature, including files shown after tool results. */
/** With [imageReferences], user images are listed by ID so the model can pass them to `generate_image`. */
class SessionMessages(private val model: AgentModel, private val imageReferences: Boolean = false) {
    private val entries = mutableListOf<Pair<Message, List<SessionEntry>>>()

    fun prompt(instructions: String, history: List<SessionEntry>): Prompt {
        entries.clear()
        val initial = prompt("kinuvo", model.parameters(instructions)) {
            if (model.instructionsInPrompt && instructions.isNotBlank()) system(instructions)
        }
        val system = initial.messages.map { (it as Message.System).copy(metaInfo = RequestMetaInfo.Empty) }
        system.forEach { entries += it to emptyList() }
        return initial.withMessages { system + messages(history.activeHistory()) }
    }

    suspend fun limitedPrompt(instructions: String, history: List<SessionEntry>, limits: ResultLimits, paging: Set<String>): Prompt {
        val original = prompt(instructions, history)
        val latest = original.messages.indexOfLast(::user)
        val limited = original.messages.mapIndexed { index, message ->
            val result = entry(message)?.singleOrNull() as? SessionEntry.ToolResult
            if (result == null) message else {
                val budget = if (index < latest) (limits.maxTokens * limits.olderShare).toInt() else limits.maxTokens
                val text = result.output.limitResult(budget, result.name in paging) { value ->
                    model.estimateTokens?.invoke(prompt("tool-result") { user(value) }, emptyList()) ?: ((value.length + 3) / 4)
                }
                if (text == result.output) message else {
                    val replacement = prompt("tool-result") { toolResult(MessagePart.Tool.Result(result.id, result.name, text, result.isError)) }.messages.single()
                    entries += replacement to listOf(result)
                    replacement
                }
            }
        }
        return original.withMessages { limited }
    }

    fun messages(history: List<SessionEntry>): List<Message> = history.flatMap { entry ->
        if (entry is SessionEntry.Compaction) return@flatMap messages(entry.history)
        val toolFiles = (entry as? SessionEntry.ToolResult)?.attachments.orEmpty()
            .filter { model.attachments != null || !it.mimeType.startsWith("image/") }
        val block = prompt("session") {
            when (entry) {
                is SessionEntry.Context -> system(entry.content)
                is SessionEntry.UserMessage -> user { modelText(entry).takeIf { it.isNotBlank() }?.let(::text); attach(entry.attachments) }
                is SessionEntry.AssistantMessage -> assistant(entry.text)
                is SessionEntry.ToolCall -> toolCall(MessagePart.Tool.Call(entry.id, entry.name, entry.args))
                is SessionEntry.ToolResult -> toolResult(MessagePart.Tool.Result(entry.id, entry.name,
                    entry.output + if (toolFiles.size < entry.attachments.size) "\n(Tool screenshot omitted; use the semantic text.)" else "", entry.isError))
                is SessionEntry.Reasoning -> if (model.replayReasoning && entry.encrypted != null) reasoning(
                    MessagePart.Reasoning(content = emptyList(), summary = listOf(entry.text), encrypted = entry.encrypted, id = entry.id)
                )
                is SessionEntry.Activation -> system(activation(entry))
                is SessionEntry.ToolReport -> system("Background work from ${entry.tool}${entry.callId?.let { " (call $it)" }.orEmpty()} finished:\n${entry.content}")
                else -> Unit
            }
        }.messages.map { message ->
            val normalized = when (message) {
                is Message.System -> message.copy(metaInfo = RequestMetaInfo.Empty)
                is Message.User -> message.copy(metaInfo = RequestMetaInfo.Empty)
                is Message.Assistant -> message.copy(metaInfo = ResponseMetaInfo.Empty)
            }
            entries += normalized to listOf(if (entry is SessionEntry.ToolResult) entry.copy(attachments = emptyList()) else entry)
            normalized
        }
        if (entry is SessionEntry.ToolResult && toolFiles.isNotEmpty())
            block + messages(listOf(SessionEntry.UserMessage(TOOL_FILES, attachments = toolFiles)))
        else block
    }

    fun estimatedEntries(messages: List<Message>): List<SessionEntry> = messages.flatMap { message ->
        val results = message.parts.filterIsInstance<MessagePart.Tool.Result>()
        if (results.isEmpty()) entries(listOf(message)) else results.map { SessionEntry.ToolResult(it.id.orEmpty(), it.tool, it.output) }
    }

    fun entries(messages: List<Message>): List<SessionEntry> = messages.flatMap { entry(it) ?: when (it) {
        is Message.System -> listOf(SessionEntry.Context(it.textContent()))
        is Message.User -> listOf(SessionEntry.UserMessage(it.textContent()))
        is Message.Assistant -> assistantEntries(it)
    } }

    fun user(message: Message): Boolean = entry(message)?.any { it is SessionEntry.UserMessage && !it.text.startsWith(TOOL_FILES) } == true
    private fun modelText(entry: SessionEntry.UserMessage): String {
        val images = entry.attachments.filter { imageReferences && it.mimeType.startsWith("image/") }
        if (images.isEmpty()) return entry.modelText
        return listOf(entry.modelText, images.joinToString("\n", "$IMAGE_REFERENCES\n") { "${it.id}: ${it.name}" }).filter(String::isNotBlank).joinToString("\n\n")
    }
    private fun entry(message: Message) = entries.lastOrNull { it.first === message }?.second

    private fun RequestMessagePartsBuilder.attach(list: List<Attachment>) = list.forEach { a ->
        val bytes = requireNotNull(model.attachments) { "This provider has no attachment storage" }(a)
        val content = AttachmentContent.Binary.Bytes(bytes)
        if (kinuvo.protocol.session.attachmentInput(a.mimeType) == "text") text("\n\nFile: ${a.name}\n${bytes.decodeToString()}\n\n")
        else if (a.mimeType.startsWith("image/")) image(AttachmentSource.Image(content, a.mimeType.substringAfter('/'), a.mimeType, a.name))
        else file(AttachmentSource.File(content = content, format = a.name.substringAfterLast('.'), mimeType = a.mimeType, fileName = a.name))
    }
}

internal fun assistantEntries(message: Message.Assistant): List<SessionEntry> = message.parts.mapNotNull { part -> when (part) {
    is MessagePart.Text -> part.text.takeIf { it.isNotEmpty() }?.let { SessionEntry.AssistantMessage(it) }
    is MessagePart.Reasoning -> SessionEntry.Reasoning((part.summary?.takeIf { it.isNotEmpty() } ?: part.content).joinToString("\n\n"), part.id, part.encrypted)
    is MessagePart.Tool.Call -> SessionEntry.ToolCall(part.id ?: Uuid.random().toString(), part.tool, part.args)
    else -> null
} }

private fun activation(a: SessionEntry.Activation) = "Skill '${a.name}' is active in this conversation:\n\n${a.content}"

internal const val TOOL_FILES = "Files from the tool results above:"

internal const val IMAGE_REFERENCES = "Image attachment IDs, for generate_image references:"
