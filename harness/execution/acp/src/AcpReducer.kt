package kinuvo.execution.acp

import java.util.Base64
import kinuvo.execution.api.ExecutionState
import kinuvo.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

/** All source updates, completion and loss pass through this queue. */
internal class AcpReducer(private val state: ExecutionState,
    private val persist: suspend (AttachmentData) -> Attachment = { error("Output attachment persistence is unavailable") },
) {
    private val queue = Mutex()
    private val sink = state.boundSink()
    private var turn = 0L
    private var active = false
    private var lost = false
    private val entries = linkedMapOf<SessionEntryIdentity, SessionEntry>()
    private val attachments = mutableMapOf<Pair<String, String>, Attachment>()
    private val tools = mutableMapOf<String, JsonObject>()
    private val finalized = mutableMapOf<Pair<String, String>, JsonObject>()
    private val currentSources = mutableMapOf<Pair<String, String>, JsonObject>()
    private fun id(source: String) = "${state.generation}:$turn:$source"
    private fun runId() = "${state.generation}:$turn"

    private suspend fun start() {
        if (active) return
        turn++
        active = true
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running))
        sink.publish(AgentEvent.RunStarted(runId()))
    }

    suspend fun submitted() = queue.withLock { check(!lost); start() }

    suspend fun update(raw: JsonObject) = queue.withLock {
        if (lost) return@withLock
        val kind = raw.string("sessionUpdate") ?: return@withLock
        if (kind == "state_update") {
            val phase = raw["state"] as? JsonObject ?: raw
            when (phase.string("state") ?: phase.string("type")) {
                "running" -> start()
                "requires_action" -> { start(); sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.RequiresAction)) }
                "idle" -> if (active) finish(phase.string("stopReason"))
            }
            return@withLock
        }
        if (kind.startsWith("user_message")) return@withLock // Runtime owns local submission and acceptance binding.
        val role = when {
            kind.startsWith("agent_message") -> "assistant"
            kind.startsWith("agent_thought") -> "reasoning"
            kind.startsWith("tool_call") -> "tool"
            else -> return@withLock
        }
        val source = raw.string(if (role == "tool") "toolCallId" else "messageId") ?: return@withLock
        val key = role to source
        if (!active && finalized.containsKey(key)) {
            if (finalized[key] != raw) sink.commit(listOf(SessionEntry.Notice(Uuid.random().toString(),
                "unsupported_history_change", "The harness changed finalized output; saved history was preserved.")))
            return@withLock
        }
        start()
        currentSources[key] = raw
        val local = id(source)
        if (role == "tool") {
            val fields = tools[source].orEmpty() + raw.filterKeys { it != "sessionUpdate" }
            val merged = if (kind == "tool_call_content_chunk") {
                val previous = tools[source]?.get("content")
                val content = (previous as? JsonArray)?.toList() ?: listOfNotNull(previous)
                JsonObject(fields + ("content" to JsonArray(content + listOfNotNull(raw["content"]))))
            } else JsonObject(fields)
            tools[source] = merged
            put(SessionEntry.ToolCall(local, merged.string("title") ?: "Tool", merged["rawInput"]?.toString().orEmpty()))
            val status = merged.string("status")
            if (status in setOf("completed", "failed", "cancelled") || "rawOutput" in merged || "content" in raw) {
                val media = media(merged["content"])
                put(SessionEntry.ToolResult(local, merged.string("title") ?: "Tool",
                    merged["rawOutput"]?.let { (it as? JsonPrimitive)?.content ?: it.toString() } ?: text(merged["content"]),
                    status in setOf("failed", "cancelled"), media))
            }
        } else {
            val identity = SessionEntryIdentity(role, local)
            val previous = entries[identity]
            val fragment = text(raw["content"])
            val old = when (previous) { is SessionEntry.AssistantMessage -> previous.text; is SessionEntry.Reasoning -> previous.text; else -> "" }
            val value = (if (kind.endsWith("_chunk")) old else "") + fragment
            val entry = if (role == "assistant") SessionEntry.AssistantMessage(value, local) else SessionEntry.Reasoning(value, messageId = local)
            entries[identity] = entry
            if (kind.endsWith("_chunk") && fragment.isNotEmpty()) sink.publish(if (role == "assistant")
                AgentEvent.TextMessageContent(local, fragment) else AgentEvent.ThinkingContent(local, fragment))
            else sink.publish(AgentEvent.ExecutionEntryChanged(entry))
            val attachments = media(raw["content"])
            if (attachments.isNotEmpty()) {
                val mediaId = "$local:media"
                val previousMedia = entries[SessionEntryIdentity("tool_result", mediaId)] as? SessionEntry.ToolResult
                val combined = if (kind.endsWith("_chunk")) (previousMedia?.attachments.orEmpty() + attachments).distinctBy { it.id } else attachments
                put(SessionEntry.ToolResult(mediaId, "Attachment", "", attachments = combined))
            }
        }
    }

    private suspend fun put(entry: SessionEntry) {
        entries[requireNotNull(entry.identity())] = entry
        sink.publish(AgentEvent.ExecutionEntryChanged(entry))
    }

    private suspend fun finish(reason: String?) {
        val interrupted = reason != null && reason !in setOf("end_turn", "max_tokens", "max_turn_requests")
        val batch = entries.values.toList()
        sink.commit(if (interrupted) listOf(SessionEntry.Notice("${runId()}:interrupted", "interrupted",
            "The harness stopped this turn ($reason).", batch)) else batch)
        sink.publish(if (interrupted) AgentEvent.RunError(runId(), "The harness stopped this turn ($reason).") else AgentEvent.RunFinished(runId()))
        sink.publish(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Idle))
        state.update(ExecutionPhase.Idle, reason)
        finalized.putAll(currentSources)
        currentSources.clear(); entries.clear(); tools.clear(); attachments.clear(); active = false
    }

    suspend fun lost(brokerLost: suspend () -> Unit) = queue.withLock {
        if (lost) return@withLock
        lost = true
        // Runtime's sink wrapper checkpoints these live drafts. Loss is never successful completion.
        brokerLost()
    }

    private suspend fun media(content: JsonElement?): List<Attachment> {
        val blocks = when (content) { is JsonArray -> content; null -> emptyList(); else -> listOf(content) }
        return blocks.flatMap { element ->
            val block = element as? JsonObject ?: return@flatMap emptyList()
            val nested = block["content"] ?: block["resource"]
            if (nested != null) return@flatMap media(nested)
            if (block.string("type") == "image" || "blob" in block) {
                val data = block.string("data") ?: block.string("blob") ?: error("Image bytes unavailable")
                val mime = block.string("mimeType") ?: "application/octet-stream"
                val extension = when (mime) {
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    "text/plain" -> "txt"
                    "text/csv" -> "csv"
                    "application/json" -> "json"
                    else -> throw IllegalArgumentException("Unsupported output MIME: $mime")
                }
                require(data.length <= (MAX_ATTACHMENT_BYTES + 2) / 3 * 4) { "Output media is too large" }
                val bytes = Base64.getDecoder().decode(data)
                require(bytes.isNotEmpty() && bytes.size <= MAX_ATTACHMENT_BYTES) { "Invalid output media size" }
                val signature = when (mime) {
                    "image/png" -> bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }
                    "image/jpeg" -> bytes.take(3) == listOf(255, 216, 255).map { it.toByte() }
                    "image/gif" -> bytes.take(6).toByteArray().decodeToString() in setOf("GIF87a", "GIF89a")
                    "image/webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
                    else -> true
                }
                require(signature) { "Output bytes do not match $mime" }
                val attachment = Attachment(Uuid.random().toString(), "Harness-output.$extension", mime, bytes.size)
                val key = mime to data
                listOf(attachments[key] ?: run {
                    require(attachments.size < MAX_ATTACHMENTS && attachments.values.sumOf { it.size } + bytes.size <= MAX_ATTACHMENT_TOTAL_BYTES) { "Too much output media" }
                    persist(AttachmentData(attachment, data)).also {
                        require(it.mimeType == mime) { "Stored output MIME does not match $mime" }
                        attachments[key] = it
                    }
                })
            } else if (block.string("type") in setOf("audio", "resource_link")) {
                put(SessionEntry.Notice(Uuid.random().toString(), "unsupported_content", "The harness returned unsupported media."))
                emptyList()
            } else emptyList()
        }
    }
}

internal fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun text(content: JsonElement?): String = when (content) {
    is JsonArray -> content.joinToString("") { text(it) }
    is JsonObject -> content.string("text") ?: when (content.string("type")) {
        "diff" -> listOfNotNull(content.string("path"), content.string("oldText"), content.string("newText")).joinToString("\n")
        "terminal" -> "Terminal: ${content.string("terminalId").orEmpty()}"
        else -> text(content["content"] ?: content["resource"])
    }
    else -> ""
}
