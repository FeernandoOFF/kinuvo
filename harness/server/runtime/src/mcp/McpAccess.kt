package kinuvo.harness.server.mcp

import co.touchlab.stately.collections.ConcurrentMutableMap
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.execution.api.KinuvoMcp
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tokens for the Server's MCP endpoint, each scoped to one Session: minted when its execution opens and revoked when it
 * closes. Until the host has set [url], nothing is granted.
 */
@OptIn(ExperimentalUuidApi::class)
class McpAccess {
    private val tokens = ConcurrentMutableMap<String, CallerScope>()

    /** Where harnesses on this machine reach the endpoint, e.g. `http://127.0.0.1:8787/mcp`. */
    @Volatile var url: String? = null

    /** A stdio command that proxies to [url], for harnesses without HTTP MCP. */
    @Volatile var bridge: List<String>? = null

    fun grant(agentId: String, sessionId: String): KinuvoMcp? {
        val url = url ?: return null
        val token = Uuid.random().toHexString() + Uuid.random().toHexString()
        tokens[token] = CallerScope(agentId, sessionId)
        return KinuvoMcp(url, token, bridge)
    }

    fun resolve(token: String): CallerScope? = tokens[token]

    fun revoke(sessionId: String) {
        tokens.block { map -> map.entries.removeAll { it.value.sessionId == sessionId } }
    }
}
