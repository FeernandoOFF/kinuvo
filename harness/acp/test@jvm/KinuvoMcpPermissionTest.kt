package kinuvo.harness.acp

import kinuvo.harness.McpServerDescriptor
import kotlinx.serialization.json.*
import kotlin.test.*

class KinuvoMcpPermissionTest {
    @Test fun onlyAnOfferedKinuvoToolIsRecognised() {
        val offered = McpServerDescriptor("http://127.0.0.1/mcp", "Bearer test", setOf("deploy"))
        fun request(server: String, name: String) = buildJsonObject {
            putJsonObject("subject") { putJsonObject("toolCall") {
                putJsonObject("rawInput") { put("serverName", server); put("toolName", name) }
            } }
        }
        assertTrue(recognisesKinuvoCall(request("kinuvo", "deploy"), offered))
        assertFalse(recognisesKinuvoCall(request("private", "deploy"), offered))
        assertFalse(recognisesKinuvoCall(request("kinuvo", "private"), offered))
        assertFalse(recognisesKinuvoCall(request("kinuvo", "deploy"), null))
        assertFalse(recognisesKinuvoCall(buildJsonObject { put("title", "kinuvo deploy") }, offered))
        assertFalse(offered.toString().contains("Bearer"))
    }
}
