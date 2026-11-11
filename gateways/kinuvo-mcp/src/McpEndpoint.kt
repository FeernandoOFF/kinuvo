package kinuvo.gateways.mcp

import arrow.core.raise.context.either
import arrow.core.raise.context.raise
import kinuvo.harness.limitResult
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.ToolCallId
import kinuvo.server.toWireError
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

class McpReply(val status: Int, val body: String? = null)

/** Stateless Streamable HTTP, with JSON responses. Credentials bind every request to one run. */
class McpEndpoint(private val access: McpAccess) {
    suspend fun handle(authorization: String?, body: String): McpReply {
        val authenticated = either { access.resolve(authorization) }
        val run = authenticated.getOrNull() ?: return McpReply(401, ProtocolJson.encodeToString(authenticated.leftOrNull()!!.toWireError()))
        val message = try { ProtocolJson.parseToJsonElement(body).jsonObject }
        catch (_: IllegalArgumentException) { return error(JsonNull, -32700, "Invalid JSON") }
        val id = message["id"] ?: return McpReply(202)
        val method = (message["method"] as? JsonPrimitive)?.content
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                    putJsonObject("serverInfo") { put("name", "kinuvo"); put("version", "1") }
                })
                "ping" -> result(id, JsonObject(emptyMap()))
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
                                run.gate.invoke(SessionEntry.ToolCall(ToolCallId(Uuid.random().toString()), name, args.toString()), run.turn)
                            }
                        }.await()
                    }
                    called.fold({ error(id, -32001, it.message, ProtocolJson.encodeToJsonElement(it.toWireError())) }, { reply ->
                        val text = reply.output.limitResult(run.maxResultTokens, run.tools.tools.firstOrNull { it.name == reply.name }?.paging == true) { (it.length + 3) / 4 }
                        result(id, buildJsonObject {
                            put("content", buildJsonArray { addJsonObject { put("type", "text"); put("text", text) } })
                            put("isError", reply.isError)
                        })
                    })
                }
                else -> error(id, -32601, "Unknown method")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: IllegalArgumentException) { error(id, -32602, "Invalid params") }
        catch (_: Exception) { error(id, -32603, "Tool request failed") }
    }
    private fun result(id: JsonElement, value: JsonElement) = McpReply(200, buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", value)
    }.toString())
    private fun error(id: JsonElement, code: Int, message: String, data: JsonElement? = null) = McpReply(200, buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message); data?.let { put("data", it) } }
    }.toString())
}
