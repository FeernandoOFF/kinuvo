package kinuvo.harness.mcp

import kinuvo.harness.core.store.readTextOrNull
import kinuvo.harness.core.store.writeAtomically
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.McpOAuthClient
import kinuvo.protocol.McpParams
import kinuvo.protocol.McpRedirect
import kinuvo.protocol.McpServerInfo
import kinuvo.protocol.McpTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlinx.io.files.Path

/** Reads and edits the `mcpServers` of one `mcp.json`, keeping `${VAR}` references and unknown keys as written. */
class McpFiles(private val file: Path, private val scope: ConfigScope) {
    private val json = Json { prettyPrint = true }

    fun list(): List<McpServerInfo> = servers().map { (name, value) -> info(name, value.jsonObject) }

    fun get(name: String): McpServerInfo? = servers()[name]?.let { info(name, it.jsonObject) }

    fun save(server: McpServerInfo): McpServerInfo {
        require(NAME.matches(server.name)) { "MCP server names use letters, digits, '.', '_' and '-'" }
        require(server.command.isNullOrBlank() != server.url.isNullOrBlank()) { "Give either a command (stdio) or a url (http)" }
        require(server.approval == null || server.approval in setOf("auto", "ask")) { "approval must be auto or ask" }
        require(server.oauth == null || server.url != null) { "OAuth is only for HTTP servers" }
        require(server.oauth == null || server.oauth!!.clientId.isNotBlank()) { "OAuth needs a client ID" }
        val old = servers()[server.name]?.jsonObject ?: JsonObject(emptyMap())
        write(servers() + (server.name to entry(server, old)))
        return info(server.name, entry(server, old))
    }

    fun remove(name: String): Boolean {
        val all = servers()
        if (name !in all) return false
        write(all - name)
        return true
    }

    private fun root(): JsonObject = file.readTextOrNull()?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())

    private fun servers(): Map<String, JsonElement> = root()["mcpServers"]?.jsonObject.orEmpty()

    private fun write(servers: Map<String, JsonElement>) {
        writeAtomically(file, json.encodeToString(JsonObject.serializer(), JsonObject(root() + ("mcpServers" to JsonObject(servers)))) + "\n")
    }

    private fun info(name: String, o: JsonObject) = McpServerInfo(
        name = name,
        scope = scope,
        command = o.str("command"),
        args = o.strings("args").orEmpty(),
        env = o.strMap("env"),
        url = o.str("url"),
        headers = o.strMap("headers"),
        includeTools = o.strings("includeTools"),
        excludeTools = o.strings("excludeTools").orEmpty(),
        approval = o.str("approval"),
        oauth = (o["oauth"] as? JsonObject)?.let { a ->
            a.str("clientId")?.let { McpOAuthClient(it, a.str("clientSecret"), a.strings("scopes").orEmpty()) }
        },
    )

    companion object {
        internal fun entry(s: McpServerInfo, old: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
            old.filterKeys { it !in KNOWN }.forEach { (k, v) -> put(k, v) }
            if (!s.command.isNullOrBlank()) {
                put("type", "stdio")
                put("command", s.command)
                if (s.args.isNotEmpty()) put("args", JsonArray(s.args.map(::JsonPrimitive)))
                if (s.env.isNotEmpty()) put("env", JsonObject(s.env.mapValues { JsonPrimitive(it.value) }))
            } else {
                put("type", "http")
                put("url", s.url)
                if (s.headers.isNotEmpty()) put("headers", JsonObject(s.headers.mapValues { JsonPrimitive(it.value) }))
            }
            s.includeTools?.let { put("includeTools", JsonArray(it.map(::JsonPrimitive))) }
            if (s.excludeTools.isNotEmpty()) put("excludeTools", JsonArray(s.excludeTools.map(::JsonPrimitive)))
            s.approval?.let { put("approval", it) }
            s.oauth?.takeIf { s.command.isNullOrBlank() }?.let { a ->
                put("oauth", buildJsonObject {
                    put("clientId", a.clientId)
                    a.clientSecret?.let { put("clientSecret", it) }
                    if (a.scopes.isNotEmpty()) put("scopes", JsonArray(a.scopes.map(::JsonPrimitive)))
                })
            }
        }

        private val NAME = Regex("[A-Za-z0-9._-]{1,64}")
        private val KNOWN = setOf("type", "command", "args", "env", "url", "headers", "includeTools", "excludeTools", "approval", "oauth")

        private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.map { it.jsonPrimitive.content }
        private fun JsonObject.strMap(key: String) = (get(key) as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
    }
}

/** Connects to [server] with its values expanded by [expand], signed in through [auth], and lists the tools it allows. */
suspend fun testMcp(server: McpServerInfo, expand: suspend (String) -> String, auth: McpAuth? = null, signInScope: String? = null): McpTestResult {
    val connection = try {
        McpServer(McpConfig.parse(server.name, McpFiles.entry(server), signInScope, expand), auth)
    } catch (e: Exception) {
        return McpTestResult(error = e.message ?: e.toString())
    }
    return try {
        withTimeoutOrNull(TEST_TIMEOUT) { McpTestResult(connection.listTools().map { it.name }.filter(connection.config::allows)) }
            ?: McpTestResult(error = "Timed out after ${TEST_TIMEOUT / 1000}s")
    } catch (e: CancellationException) {
        throw e
    } catch (e: McpAuthRequired) {
        McpTestResult(error = e.message, needsLogin = true)
    } catch (e: Exception) {
        McpTestResult(error = e.message ?: e.toString())
    } finally {
        connection.close()
    }
}

/** Starts an OAuth sign-in to [server], its values expanded by [expand], for [owner]; returns the URL to open. */
suspend fun loginMcp(server: McpServerInfo, auth: McpAuth, signInScope: String, redirect: McpRedirect, owner: McpParams, expand: suspend (String) -> String): String {
    val config = McpConfig.parse(server.name, McpFiles.entry(server), signInScope, expand) as? McpServerConfig.Http
        ?: error("Only HTTP MCP servers sign in; '${server.name}' runs a command")
    return auth.begin(config, redirect, owner)
}

private const val TEST_TIMEOUT = 30_000L
