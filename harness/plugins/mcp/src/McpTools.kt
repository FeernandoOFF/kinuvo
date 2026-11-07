package kinuvo.harness.mcp

import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kinuvo.harness.core.plugin.modelAlias
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.ToolIdentity

import kinuvo.protocol.Risk
import kinuvo.harness.core.tools.Tool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kinuvo.protocol.McpAppRef
import kinuvo.protocol.McpAppResource
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.SessionEntry
import kinuvo.harness.core.tools.ToolRun
import kotlinx.serialization.json.*
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray

class McpTool(
    private val server: McpServer,
    private val toolName: String,
    description: String,
    schema: JsonObject,
    readOnly: Boolean = false,
    destructive: Boolean = false,
    private val app: McpAppRef? = null,
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
        return render(result).also { if (result.isError == true) error(it.ifBlank { "tool failed" }) }
    }

    override suspend fun executeResult(args: JsonObject, callId: String, run: ToolRun): SessionEntry.ToolResult {
        val result = server.callTool(toolName, args)
        val shown = if (app != null && result.isError != true) "\n\nThe user sees this result in the tool's interactive app in the chat." else ""
        return SessionEntry.ToolResult(callId, descriptor.name, render(result) + shown, isError = result.isError == true,
            app = app, appResult = ProtocolJson.encodeToJsonElement(CallToolResult.serializer(), result).jsonObject)
    }

    private fun render(result: CallToolResult): String {
        val text = result.content.joinToString("\n") {
            when (it) {
                is TextContent -> it.text
                is ImageContent -> "[image: ${it.mimeType}]"
                else -> it.toString()
            }
        }.ifBlank { result.structuredContent?.toString().orEmpty() }
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
    private val apps = McpAppCache()
    private var appTools = emptyMap<Pair<String, String>, Tool>()

    suspend fun resource(ref: McpAppRef): McpAppResource {
        apps.cached(ref)?.let { return it }
        if (servers.isEmpty()) tools()
        val server = servers.values.firstOrNull { it.config.instanceId == ref.serverId } ?: error("The MCP server for this app is no longer configured")
        val current = apps.load(server, ref.resourceUri)
        check(current == ref) { "MCP App resource changed; run the tool again" }
        return apps.get(ref)
    }

    suspend fun appTool(ref: McpAppRef, name: String): Tool {
        resource(ref)
        return appTools[ref.serverId to name] ?: error("Tool '$name' is not available to this MCP App")
    }

    private val removeHook = onShutdown { servers.values.forEach { it.destroyNow() } }

    /** Current tools of every server; failing servers are reported instead of thrown. */
    suspend fun tools(): Pair<List<Tool>, List<String>> {
        val (configs, errors) = expand?.let { McpConfig.load(configFiles, signInScopes, agentId, it) } ?: McpConfig.load(configFiles, signInScopes, agentId)
        val warnings = errors.toMutableList()
        val old = servers
        servers = configs.associate { cfg -> cfg.name to (old[cfg.name]?.takeIf { it.config == cfg && it.authGeneration == generation(cfg) } ?: McpServer(cfg, auth)) }
        if (old.values.any { it !in servers.values }) apps.clear()
        (old.values - servers.values.toSet()).forEach { runCatching { it.close() } }
        val callable = mutableMapOf<Pair<String, String>, Tool>()
        val tools = servers.values.flatMap { server ->
            try {
                server.listTools().filter { server.config.allows(it.name) }.mapNotNull { t ->
                    val schema = buildJsonObject {
                        t.inputSchema.properties?.let { put("properties", it) }
                        t.inputSchema.required?.let { req -> put("required", JsonArray(req.map(::JsonPrimitive))) }
                        t.inputSchema.defs?.let { put("\$defs", it) }
                    }
                    val ui = t.meta?.get("ui") as? JsonObject
                    val uri = (ui?.get("resourceUri") as? JsonPrimitive)?.content
                    // A tool whose UI can't be read still works, without its app.
                    val ref = uri?.let { try { apps.load(server, it) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
                    val tool = McpTool(server, t.name, t.description ?: t.title ?: t.name, schema, t.annotations?.readOnlyHint == true, t.annotations?.destructiveHint == true, ref)
                    if (ui.visibleTo("app")) callable[server.config.instanceId to t.name] = tool
                    tool.takeIf { ui.visibleTo("model") }
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
        appTools = callable
        return tools to warnings
    }

    /** HTTP servers that refused Kinuvo until the user signs in. */
    fun needingSignIn(): List<McpServerConfig.Http> =
        servers.values.mapNotNull { it.config as? McpServerConfig.Http }.filter { auth?.isRequired(it.signInId) == true }

    private fun generation(cfg: McpServerConfig) = (cfg as? McpServerConfig.Http)?.let { auth?.generation(it.signInId) } ?: 0

    suspend fun close() {
        servers.values.forEach { it.close() }
        servers = emptyMap()
        apps.clear()
        appTools = emptyMap()
        removeHook()
    }
}
