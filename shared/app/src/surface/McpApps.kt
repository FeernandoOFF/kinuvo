package kinuvo.app.surface

import kinuvo.app.connection.ConnectedServer
import kinuvo.protocol.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject

/** MCP App HTML by app reference, which includes its content hash, for the connected Server's lifetime. */
class McpApps(private val server: ConnectedServer) {
    private val cache = LinkedHashMap<McpAppRef, Deferred<McpAppResource>>()

    suspend fun resource(ref: McpAppRef, request: McpAppRequest): McpAppResource = server.run {
        cache.getOrPut(ref) {
            if (cache.size >= 16) cache.remove(cache.keys.first())
            server.scope.async { server.call { it.call(Api.AppResource, request) } }
        }.await()
    }

    suspend fun callTool(request: McpAppRequest, name: String, arguments: JsonObject): JsonObject =
        server.call { it.call(Api.AppCallTool, McpAppToolRequest(request, name, arguments)) }

    internal fun clear() { cache.clear() }
}
