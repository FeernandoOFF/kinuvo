package kinuvo.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpAppRef(val serverId: String, val resourceUri: String, val hash: String)

@Serializable
data class McpAppResource(val html: String, val ui: JsonObject = JsonObject(emptyMap()))

@Serializable
data class McpAppRequest(val agentId: String, val sessionId: String, val callId: String)

@Serializable
data class McpAppToolRequest(val app: McpAppRequest, val name: String, val arguments: JsonObject = JsonObject(emptyMap()))
