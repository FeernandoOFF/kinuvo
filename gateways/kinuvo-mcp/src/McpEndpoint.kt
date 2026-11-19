package kinuvo.gateways.mcp

import arrow.core.raise.context.either
import arrow.core.raise.context.raise
import kinuvo.harness.limitResult
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.session.*
import kotlin.io.encoding.Base64
import kinuvo.server.toWireError
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

class McpReply(val status: Int, val body: String? = null)

/** Stateless Streamable HTTP, with JSON responses. Credentials bind requests to an execution Session. */
class McpEndpoint(private val access: McpAccess) {
    suspend fun handle(authorization: String?, body: String): McpReply {
        val authenticated = either { access.resolve(authorization) }
        val session = authenticated.getOrNull() ?: return McpReply(401, ProtocolJson.encodeToString(authenticated.leftOrNull()!!.toWireError()))
        val message = try { ProtocolJson.parseToJsonElement(body).jsonObject }
        catch (_: IllegalArgumentException) { return error(JsonNull, -32700, "Invalid JSON") }
        val id = message["id"] ?: return McpReply(202)
        val method = (message["method"] as? JsonPrimitive)?.content
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (method == "ping") return result(id, JsonObject(emptyMap()))
        if (method !in setOf("initialize", "tools/list", "tools/call")) return error(id, -32601, "Unknown method")
        val run = session.run?.takeIf { it.job.isActive } ?: return error(id, -32001, "No turn is active")
        return try {
            when (method) {
                "initialize" -> result(id, buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    run.instructions?.let { put("instructions", it) }
                    putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                    putJsonObject("serverInfo") { put("name", "kinuvo"); put("version", "1") }
                })
                "tools/list" -> result(id, buildJsonObject {
                    put("tools", JsonArray(run.tools.advertised.map { tool -> buildJsonObject {
                        put("name", tool.name); put("description", tool.description); put("inputSchema", tool.schema)
                    } }))
                })
                "tools/call" -> {
                    val called = either {
                        val name = requireNotNull((params["name"] as? JsonPrimitive)?.content)
                        if (run.tools.tools.none { it.name == name }) raise(McpError.UnknownTool)
                        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                        CoroutineScope(currentCoroutineContext() + run.job).async {
                            run.calls.withLock {
                                val reply = run.gate.invoke(SessionEntry.ToolCall(ToolCallId(Uuid.random().toString()), name, args.toString()), run.turn)
                                val text = reply.output.limitResult(run.maxResultTokens, run.tools.tools.firstOrNull { it.name == reply.name }?.paging == true) { (it.length + 3) / 4 }
                                result(id, buildJsonObject {
                                    put("content", content(run, reply, text))
                                    put("isError", reply.isError)
                                })
                            }
                        }.await()
                    }
                    called.fold({ error(id, -32001, it.message, ProtocolJson.encodeToJsonElement(it.toWireError())) }, { it })
                }
                else -> error(id, -32601, "Unknown method")
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (run.job.isActive) throw e
            error(id, -32001, "No turn is active")
        }
        catch (_: IllegalArgumentException) { error(id, -32602, "Invalid params") }
        catch (_: Exception) { error(id, -32603, "Tool request failed") }
    }
    private suspend fun content(run: McpAccess.Run, reply: SessionEntry.ToolResult, text: String) = buildJsonArray {
        addJsonObject { put("type", "text"); put("text", text) }
        var remaining = MAX_ATTACHMENT_TOTAL_BYTES
        for (attachment in reply.attachments.take(MAX_ATTACHMENTS)) {
            val bytes = if (attachment.mimeType.startsWith("image/") &&
                attachment.mimeType in ATTACHMENT_MIME_TYPES && attachment.size in 1..minOf(remaining, MAX_ATTACHMENT_BYTES)) {
                try { run.attachmentBytes(attachment).takeIf { it.size == attachment.size } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            } else null
            if (bytes != null) {
                remaining -= bytes.size
                addJsonObject { put("type", "image"); put("data", Base64.encode(bytes)); put("mimeType", attachment.mimeType) }
            } else {
                addJsonObject { put("type", "text"); put("text", "Attachment: ${attachment.name} (${attachment.mimeType})") }
            }
        }
    }

    private fun result(id: JsonElement, value: JsonElement) = McpReply(200, buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", value)
    }.toString())
    private fun error(id: JsonElement, code: Int, message: String, data: JsonElement? = null) = McpReply(200, buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message); data?.let { put("data", it) } }
    }.toString())
}
