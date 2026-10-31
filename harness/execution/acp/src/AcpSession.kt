@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package kinuvo.execution.acp

import com.agentclientprotocol.client.v2.ClientSession
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.model.v2.SessionUpdate as AcpSessionUpdate
import java.nio.ByteBuffer
import kinuvo.execution.api.*
import kinuvo.protocol.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

class AcpSession internal constructor(
    private val session: ClientSession,
    private val connection: AcpConnection,
    private val state: ExecutionState,
    private val broker: ExecutionBroker,
    private val reducer: AcpReducer,
    private val cancelRequests: suspend () -> Unit,
    override val capabilities: ExecutionCapabilities,
) : ExecutionSession {
    private val commands = Mutex()

    override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance = coroutineScope {
        val content = input.content(capabilities)
        val response = commands.withLock {
            reducer.submitted()
            async(start = CoroutineStart.UNDISPATCHED) { connection.prompt(session, content) }
        }.await()
        state.accepted()
        ExecutionAcceptance((response as? JsonObject)?.string("messageId"))
    }

    override suspend fun cancel() = commands.withLock { connection.cancel(session); cancelRequests() }

    override suspend fun answer(requestId: String, answer: JsonElement) {
        check(broker.answer(requestId, answer)) { "Request is no longer pending" }
    }

    suspend fun collect() {
        session.updates.collect { received ->
            reducer.update(ProtocolJson.encodeToJsonElement(AcpSessionUpdate.serializer(), received.update).jsonObject)
        }
    }
}

internal fun ExecutionInput.content(capabilities: ExecutionCapabilities): List<ContentBlock> = buildList {
    if (text.isNotEmpty()) add(ContentBlock.Text(text))
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
