@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package kinuvo.harness.acp

import kinuvo.protocol.surface.modelText

import com.agentclientprotocol.client.v2.ClientSessionOperations
import com.agentclientprotocol.client.v2.ClientSession
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.model.v2.SessionUpdate as AcpSessionUpdate
import java.nio.ByteBuffer
import kinuvo.harness.*
import kinuvo.protocol.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kinuvo.protocol.surface.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

class AcpSession internal constructor(
    session: ClientSession?,
    private val previous: String?,
    private val cwd: String,
    private val operations: ClientSessionOperations,
    private val connection: AcpConnection,
    private val state: ExecutionState,
    private val broker: ExecutionBroker,
    private val reducer: AcpReducer,
    private val cancelRequests: suspend () -> Unit,
    override val capabilities: ExecutionCapabilities,
    override val acceptsMcp: Boolean = false,
) : ExecutionSession {
    private val commands = Mutex()
    private val sessions = MutableStateFlow(session)
    private val session get() = requireNotNull(sessions.value)

    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance = coroutineScope {
        if (sessions.value == null || input.mcp != null && connection.supportsMcp()) {
            val id = sessions.value?.sessionId?.toString() ?: previous
            val next = if (id == null) connection.newSession(cwd, operations, input.mcp)
                else connection.resumeSession(id, cwd, operations, input.mcp)
            sessions.value = next
            state.reference(next.sessionId.toString())
        }
        val content = input.content(capabilities)
        val response = commands.withLock {
            reducer.submitted()
            async(start = CoroutineStart.UNDISPATCHED) { connection.prompt(session, content) }
        }.await()
        state.accepted()
        ExecutionAcceptance((response as? JsonObject)?.string("messageId"))
    }

    override suspend fun cancel() = commands.withLock { sessions.value?.let(connection::cancel); cancelRequests() }

    override suspend fun answer(requestId: InteractionId, answer: InteractionAnswer) {
        check(broker.answer(requestId, answer.toHarnessJson())) { "Request is no longer pending" }
    }

    suspend fun collect() {
        sessions.filterNotNull().collectLatest { active ->
            active.updates.collect { received ->
                reducer.update(ProtocolJson.encodeToJsonElement(AcpSessionUpdate.serializer(), received.update).jsonObject)
            }
        }
    }
}

internal fun ExecutionInput.content(capabilities: ExecutionCapabilities): List<ContentBlock> = buildList {
    if (text.isNotEmpty()) add(ContentBlock.Text(action?.modelText(text) ?: text))
    require(attachments.size <= MAX_ATTACHMENTS)
    var total = 0
    attachments.forEach { data ->
        val attachment = data.attachment
        require(attachment.mimeType in capabilities.supportedMimeTypes) { "Unsupported attachment type: ${attachment.mimeType}" }
        val bytes = Base64.getDecoder().decode(data.base64)
        require(bytes.size <= MAX_ATTACHMENT_BYTES)
        total += bytes.size
        require(total <= MAX_ATTACHMENT_TOTAL_BYTES)
        if (attachment.mimeType.startsWith("image/")) add(ContentBlock.Image(data.base64, attachment.mimeType))
        else {
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            val block = buildJsonObject {
                put("type", "resource")
                putJsonObject("resource") {
                    put("uri", "kinuvo-attachment:${attachment.id}"); put("mimeType", attachment.mimeType); put("text", text)
                }
            }
            add(ProtocolJson.decodeFromJsonElement(ContentBlock.serializer(), block))
        }
    }
    require(isNotEmpty()) { "Prompt is empty" }
}

internal fun PromptCapabilities?.supportedMimeTypes(): Set<String> = ATTACHMENT_MIME_TYPES.filterTo(mutableSetOf()) {
    (it.startsWith("image/") && this?.image != null) ||
        ((it.startsWith("text/") || it == "application/json") && this?.embeddedContext != null)
}
