package kinuvo.harness.mcp

import kinuvo.harness.core.Secrets
import kinuvo.protocol.McpOAuthClient
import kotlinx.serialization.json.*
import kinuvo.harness.core.store.readTextOrNull
import kotlinx.io.files.Path

/** One entry of a Claude Code-style `mcpServers` config. */
sealed interface McpServerConfig {
    val name: String
    val instanceId: String
    val includeTools: List<String>?
    val excludeTools: List<String>
    /** "auto" or "ask" overrides the server's readOnlyHint annotations. */
    val approval: String?
    /** The Agents that use a shared server; null for every Agent. */
    val agents: List<String>?

    data class Stdio(
        override val name: String,
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        override val includeTools: List<String>? = null,
        override val excludeTools: List<String> = emptyList(),
        override val approval: String? = null,
        override val instanceId: String = name,
        override val agents: List<String>? = null,
    ) : McpServerConfig

    data class Http(
        override val name: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        override val includeTools: List<String>? = null,
        override val excludeTools: List<String> = emptyList(),
        override val approval: String? = null,
        val oauth: McpOAuthClient? = null,
        /** Where its OAuth sign-in is kept: `shared/<name>` or `agents/<id>/<name>`, so it follows the config's scope. */
        val signInId: String = name,
        override val agents: List<String>? = null,
    ) : McpServerConfig {
        override val instanceId get() = signInId
    }

    fun allows(tool: String) = (includeTools == null || tool in includeTools!!) && tool !in excludeTools

    fun usedBy(agentId: String) = agents?.contains(agentId) != false
}

object McpConfig {
    /**
     * Servers from [files] in order; later files override earlier ones by name, with values expanded by [expand] (given
     * the server's name). [signInScopes] name each file's scope for [McpServerConfig.Http.signInId]. Invalid entries are
     * reported, not thrown. With [agentId], shared servers that Agent doesn't use are left out.
     */
    suspend fun load(
        files: List<Path>,
        signInScopes: List<String> = emptyList(),
        agentId: String? = null,
        expand: suspend (server: String, value: String) -> String = ::systemEnv,
    ): Pair<List<McpServerConfig>, List<String>> {
        val servers = linkedMapOf<String, McpServerConfig>()
        val errors = mutableListOf<String>()
        for ((i, file) in files.withIndex()) {
            val text = file.readTextOrNull() ?: continue
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }
                .getOrElse { errors += "$file: invalid JSON (${it.message})"; continue }
            val entries = root["mcpServers"]?.jsonObject ?: continue
            for ((name, value) in entries) {
                runCatching { parse(name, value.jsonObject, signInScopes.getOrNull(i)) { expand(name, it) } }
                    .onSuccess { if (agentId == null || it.usedBy(agentId)) servers[name] = it else servers.remove(name) }
                    .onFailure { errors += "MCP server '$name': ${it.message}" }
            }
        }
        return servers.values.toList() to errors
    }

    internal suspend fun parse(name: String, obj: JsonObject, signInScope: String? = null, expand: suspend (String) -> String = { systemEnv(name, it) }): McpServerConfig {
        suspend fun str(key: String) = obj[key]?.jsonPrimitive?.content?.let { expand(it) }
        suspend fun list(key: String) = obj[key]?.jsonArray?.map { expand(it.jsonPrimitive.content) }
        listOfNotNull(obj["command"]?.jsonPrimitive?.content).plus(obj["args"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
            .forEach { require(!Secrets.hasSecret(it)) { "Secret references can't go in command or args, where other processes can see them; put them in env" } }
        suspend fun map(key: String) = obj[key]?.jsonObject?.mapValues { expand(it.value.jsonPrimitive.content) }.orEmpty()
        val include = obj["includeTools"]?.jsonArray?.map { it.jsonPrimitive.content }
        val exclude = obj["excludeTools"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val approval = str("approval")?.also { require(it == "auto" || it == "ask") { "approval must be auto or ask" } }
        val agents = obj["agents"]?.jsonArray?.map { it.jsonPrimitive.content }
        return when (val type = str("type") ?: if (obj.containsKey("command")) "stdio" else "http") {
            "stdio" -> McpServerConfig.Stdio(name, requireNotNull(str("command")) { "missing command" }, list("args").orEmpty(), map("env"), include, exclude, approval,
                signInScope?.let { "$it/$name" } ?: name, agents)
            "http" -> McpServerConfig.Http(
                name, requireNotNull(str("url")) { "missing url" }, map("headers"), include, exclude, approval, oauth(obj["oauth"], expand),
                signInScope?.let { "$it/$name" } ?: name, agents,
            )
            else -> error("unsupported type '$type' (use stdio or http)")
        }
    }

    private suspend fun oauth(value: JsonElement?, expand: suspend (String) -> String): McpOAuthClient? {
        val o = value?.jsonObject ?: return null
        return McpOAuthClient(
            expand(requireNotNull(o["clientId"]?.jsonPrimitive?.content) { "oauth needs a clientId" }),
            o["clientSecret"]?.jsonPrimitive?.content?.let { expand(it) },
            o["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }

    private suspend fun systemEnv(server: String, value: String) = Secrets().expand(value, kinuvo.harness.core.store.processEnv(), "Kinuvo: MCP server $server")
}
