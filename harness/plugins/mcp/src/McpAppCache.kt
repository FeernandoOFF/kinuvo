package kinuvo.harness.mcp

import kinuvo.harness.core.sha256
import kinuvo.protocol.McpAppRef
import kinuvo.protocol.McpAppResource
import kinuvo.protocol.ProtocolJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal fun JsonObject?.visibleTo(audience: String): Boolean {
    val visibility = this?.get("visibility") ?: return true
    return (visibility as? JsonArray)?.contains(JsonPrimitive(audience)) == true
}

internal class McpAppCache {
    private val lock = Mutex()
    private val refs = mutableMapOf<Triple<String, String, Int>, McpAppRef>()
    private val content = mutableMapOf<McpAppRef, McpAppResource>()

    suspend fun load(server: McpServer, uri: String): McpAppRef = lock.withLock {
        require(uri.startsWith("ui://")) { "MCP App resources must use ui://" }
        val key = Triple(server.config.instanceId, uri, server.connections)
        refs[key]?.let { return@withLock it }
        val result = ProtocolJson.encodeToJsonElement(server.readResource(uri)).jsonObject
        val item = result["contents"]!!.jsonArray.single().jsonObject
        require(item["uri"]?.jsonPrimitive?.content == uri) { "Resource URI mismatch" }
        require(item["mimeType"]?.jsonPrimitive?.content == "text/html;profile=mcp-app") { "Unsupported MCP App MIME type" }
        val html = item["text"]?.jsonPrimitive?.content ?: item["blob"]?.jsonPrimitive?.content?.let {
            require(it.length <= 700_000) { "MCP App resource too large" }
            Base64.decode(it).decodeToString(throwOnInvalidSequence = true)
        } ?: error("MCP App resource has no HTML")
        require(html.isNotBlank() && html.encodeToByteArray().size <= 512 * 1024) { "MCP App resource empty or too large" }
        val ui = (item["_meta"] as? JsonObject)?.get("ui") as? JsonObject ?: JsonObject(emptyMap())
        val resource = McpAppResource(html, ui)
        val hash = sha256(ProtocolJson.encodeToString(resource).encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        val ref = McpAppRef(server.config.instanceId, uri, hash)
        content[ref] = resource
        refs[key] = ref
        ref
    }

    suspend fun cached(ref: McpAppRef) = lock.withLock { content[ref] }
    suspend fun get(ref: McpAppRef) = cached(ref) ?: error("MCP App resource is no longer available")
    fun clear() { refs.clear(); content.clear() }
}
