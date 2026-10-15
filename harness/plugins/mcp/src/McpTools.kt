package kinuvo.harness.mcp

import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kinuvo.harness.core.modelAlias
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.ToolIdentity

import kinuvo.protocol.Risk
import kinuvo.harness.core.tools.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.io.files.Path

class McpTool(
    private val server: McpServer,
    private val toolName: String,
    description: String,
    schema: JsonObject,
    readOnly: Boolean = false,
    destructive: Boolean = false,
) : Tool {
    override val identity = identity(server.config, toolName)
    override val displayName = toolName
    override val descriptor = RawSchemaToolDescriptor(identity.modelAlias(), description, schema)
    override val risk = when (server.config.approval) {
        "auto" -> Risk.Low
        "ask" -> Risk.Medium
        else -> if (readOnly) Risk.Low else if (destructive) Risk.High else Risk.Medium
    }

    override suspend fun execute(args: JsonObject): String {
        val result = server.callTool(toolName, args)
        val text = result.content.joinToString("\n") {
            when (it) {
                is TextContent -> it.text
                is ImageContent -> "[image: ${it.mimeType}]"
                else -> it.toString()
            }
        }.ifBlank { result.structuredContent?.toString().orEmpty() }
        if (result.isError == true) error(text.ifBlank { "tool failed" })
        return text
    }

    companion object {
        fun identity(config: McpServerConfig, tool: String) = ToolIdentity(PluginInstanceId("kinuvo.mcp", config.instanceId), tool)

    }
}

/**
 * The MCP servers of one Agent, with config values expanded by [expand]. Servers connect on first use and stay up until
 * [close]. HTTP servers sign in through [auth], their sign-ins kept per file under [signInScopes].
 */
class McpManager(
    private val configFiles: List<Path>,
    private val expand: (suspend (server: String, value: String) -> String)? = null,
    private val auth: McpAuth? = null,
    private val signInScopes: List<String> = emptyList(),
    private val agentId: String? = null,
) {
    private var servers: Map<String, McpServer> = emptyMap()
    private val removeHook = onShutdown { servers.values.forEach { it.destroyNow() } }

    /** Current tools of every server; failing servers are reported instead of thrown. */
    suspend fun tools(): Pair<List<Tool>, List<String>> {
        val (configs, errors) = expand?.let { McpConfig.load(configFiles, signInScopes, agentId, it) } ?: McpConfig.load(configFiles, signInScopes, agentId)
        val warnings = errors.toMutableList()
        val old = servers
        servers = configs.associate { cfg -> cfg.name to (old[cfg.name]?.takeIf { it.config == cfg && it.authGeneration == generation(cfg) } ?: McpServer(cfg, auth)) }
        (old.values - servers.values.toSet()).forEach { runCatching { it.close() } }
        val tools = servers.values.flatMap { server ->
            try {
                server.listTools().filter { server.config.allows(it.name) }.map { t ->
                    val schema = buildJsonObject {
                        t.inputSchema.properties?.let { put("properties", it) }
                        t.inputSchema.required?.let { req -> put("required", kotlinx.serialization.json.JsonArray(req.map(::JsonPrimitive))) }
                        t.inputSchema.defs?.let { put("\$defs", it) }
                    }
                    McpTool(server, t.name, t.description ?: t.title ?: t.name, schema, t.annotations?.readOnlyHint == true, t.annotations?.destructiveHint == true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpAuthRequired) {
                warnings += e.message!!
                emptyList()
            } catch (e: Exception) {
                warnings += "MCP server '${server.config.name}' unavailable: ${e.message}"
                emptyList()
            }
        }
        return tools to warnings
    }

    /** HTTP servers that refused Kinuvo until the user signs in. */
    fun needingSignIn(): List<McpServerConfig.Http> =
        servers.values.mapNotNull { it.config as? McpServerConfig.Http }.filter { auth?.isRequired(it.signInId) == true }

    private fun generation(cfg: McpServerConfig) = (cfg as? McpServerConfig.Http)?.let { auth?.generation(it.signInId) } ?: 0

    suspend fun close() {
        servers.values.forEach { it.close() }
        servers = emptyMap()
        removeHook()
    }
}
