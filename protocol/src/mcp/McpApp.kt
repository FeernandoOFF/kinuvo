package kinuvo.protocol.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId

@Serializable
data class McpAppRef(val serverId: String, val resourceUri: String, val hash: String)

@Serializable
data class McpAppResource(val html: String, val ui: JsonObject = JsonObject(emptyMap()))

@Serializable
data class McpAppRequest(val agentId: AgentId, val sessionId: SessionId, val callId: ToolCallId)

@Serializable
data class McpAppToolRequest(val app: McpAppRequest, val name: String, val arguments: JsonObject = JsonObject(emptyMap()))
