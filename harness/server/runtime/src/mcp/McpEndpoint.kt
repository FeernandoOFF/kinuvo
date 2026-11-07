package kinuvo.harness.server.mcp

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kinuvo.protocol.ProtocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** [body] is null when there is none to send. */
class McpReply(val status: Int, val body: String? = null)

/**
 * The Server's tools as an MCP server over streamable HTTP (JSON replies only): a POSTed JSON-RPC message in, its reply
 * out. The bearer token names the Session the call comes from, and its tools only see that Session's Agent.
 */
class McpEndpoint(private val access: McpAccess, private val gateway: AgentToolGateway, private val version: String = "dev") {
    suspend fun handle(authorization: String?, body: String): McpReply {
        val scope = authorization?.removePrefix("Bearer ")?.takeIf { it != authorization }?.let(access::resolve) ?: return McpReply(401)
        val message = runCatching { ProtocolJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return error(null, PARSE_ERROR, "Invalid JSON-RPC message")
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.content
        if (method == null || id == null) return McpReply(202)
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(gateway.tools(scope).map { schema(it.descriptor) })) })
                "tools/call" -> result(id, call(scope, params))
                else -> error(id, METHOD_NOT_FOUND, "Unknown method '$method'")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            error(id, INVALID_PARAMS, e.message ?: "Invalid params")
        } catch (e: Exception) {
            error(id, INTERNAL_ERROR, e.message ?: "Internal error")
        }
    }

    private fun initialize(params: JsonObject) = buildJsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.content
        put("protocolVersion", asked?.takeIf { it in SUPPORTED } ?: SUPPORTED.first())
        putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
        putJsonObject("serverInfo") { put("name", "kinuvo"); put("version", version) }
    }

    private suspend fun call(scope: CallerScope, params: JsonObject): JsonObject {
        val name = requireNotNull((params["name"] as? JsonPrimitive)?.content) { "Missing 'name'" }
        val reply = gateway.call(scope, name, params["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
        return buildJsonObject {
            put("content", buildJsonArray { addJsonObject { put("type", "text"); put("text", reply.text) } })
            put("isError", reply.isError)
        }
    }

    private fun schema(d: ToolDescriptor) = buildJsonObject {
        put("name", d.name)
        put("description", d.description)
        put("inputSchema", if (d is RawSchemaToolDescriptor) d.schema else buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                (d.requiredParameters + d.optionalParameters).forEach { p -> putJsonObject(p.name) { put("type", p.type.name.lowercase()); put("description", p.description) } }
            }
            put("required", buildJsonArray { d.requiredParameters.forEach { add(JsonPrimitive(it.name)) } })
        })
    }

    private fun result(id: JsonElement, result: JsonElement) = McpReply(200, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString())

    private fun error(id: JsonElement?, code: Int, message: String) = McpReply(200, buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString())

    private companion object {
        val SUPPORTED = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}
