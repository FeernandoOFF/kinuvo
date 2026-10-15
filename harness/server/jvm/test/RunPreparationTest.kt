package kinuvo.harness.server

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.exec.ExecPlugin
import kinuvo.harness.executor.ExecutorPlugin
import kinuvo.harness.mcp.McpServerConfig
import kinuvo.harness.mcp.McpTool
import kinuvo.harness.mcp.McpPlugin
import kinuvo.protocol.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.*

class RunPreparationTest {
    private val home = Files.createTempDirectory("run-preparation").toFile()
    private val seen = mutableListOf<List<String>>()
    private var modelsBuilt = 0
    private val provider = object : ModelProvider {
        override val id = "test"
        override suspend fun models() = listOf(
            ModelInfo("tools", tools = true, context = 100_000),
            ModelInfo("text", tools = false),
            ModelInfo("limited", tools = true, toolIds = setOf("ping")),
        )
        override fun usableTools(model: String, tools: List<ToolDescriptor>) = tools.filter {
            it.name != "ping" || it.requiredParameters.any { p -> p.name == "message" }
        }
        override fun model(config: AgentConfig): Model {
            modelsBuilt++
            return Model { _, _, tools ->
                seen += tools.map { it.name }
                flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
            }
        }
    }
    private suspend fun harness(plugins: List<HarnessPlugin> = emptyList(), providers: List<ModelProvider> = listOf(provider)) =
        HarnessRuntime(ServerInstallation.assemble(AgentFiles(Path(home.path)), plugins = plugins, modelProviders = providers))
    private suspend fun agent(harness: HarnessRuntime, tools: List<String> = emptyList()) = harness.store.agents().single().let {
        it.copy(config = it.config.copy(provider = "test", model = "tools", tools = tools))
    }
    private suspend fun inspect(harness: HarnessRuntime, agent: Agent, session: String? = null, settings: SessionSettings? = null) =
        harness.capabilities(agent, session, settings, ServerCapabilities(harness.serverId, harness.features))

    @Test
    fun builtInPluginToolsAreReportedAndDisabledGroupsAreAbsent() = runBlocking {
        val harness = harness(listOf(ExecutorPlugin()))
        try {
            val agent = agent(harness, listOf("knowledge", "tasks", "run_code"))
            val session = harness.newSession(agent)
            val reported = inspect(harness, agent, session.id).tools.map { it.identity!!.localId }.toSet()
            assertEquals(setOf("knowledge", "knowledge_rewrite", "tasks", "run_code"), reported)
            harness.runner(agent, session.id).run(session.id, "hi").toList()
            assertEquals(inspect(harness, agent, session.id).tools.map { it.id }.toSet(), seen.last().toSet())
            assertEquals(emptyList(), inspect(harness, agent(harness)).tools)
        } finally { harness.close() }
    }

    @Test
    fun execInspectionReportsAllToolsWithoutStartingBackend() = runBlocking {
        val harness = harness(listOf(ExecPlugin(AgentFiles(Path(home.path)))))
        try {
            val agent = agent(harness).let { it.copy(config = it.config.copy(exec = "ssh", workspace = listOf("/workspace"))) }
            val tools = inspect(harness, agent).tools
            assertEquals(setOf("shell", "read_file", "write_file", "edit_file", "list_files", "process_output", "process_kill"), tools.map { it.identity!!.localId }.toSet())
            assertTrue(tools.all { it.state.availability == Availability.Unavailable })
            assertEquals(emptyList(), inspect(harness, agent(harness)).tools)
            val host = agent.copy(config = agent.config.copy(exec = "host", workspace = listOf(home.path)))
            val session = harness.newSession(host)
            assertTrue(inspect(harness, host, session.id).tools.all { it.state.support == Support.Unknown })
            harness.runner(host, session.id).run(session.id, "hi").toList()
            assertEquals(tools.map { it.id }.toSet(), seen.last().toSet())
        } finally { harness.close() }
    }

    @Test
    fun mcpDiscoveryIsLazyAndItsCachedToolsMatchExecution() = runBlocking {
        val marker = home.resolve("connections")
        val script = home.resolve("mcp.py")
        script.writeText("""
            import json, sys
            with open("${marker.path}", "a") as f:
                f.write("started\n")
            for line in sys.stdin:
                request = json.loads(line)
                if "id" not in request:
                    continue
                method = request["method"]
                if method == "initialize":
                    result = {"protocolVersion": request["params"]["protocolVersion"], "capabilities": {"tools": {}}, "serverInfo": {"name": "test", "version": "1"}}
                elif method == "tools/list":
                    result = {"tools": [{"name": name, "description": name, "inputSchema": {"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]}} for name in ["echo", "hidden"]]}
                else:
                    result = {}
                print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}), flush=True)
        """.trimIndent())
        home.resolve("mcp.json").writeText("""{"mcpServers":{"local":{"command":"/usr/bin/python3","args":["${script.path}"],"excludeTools":["hidden"]}}}""")
        val harness = harness(listOf(McpPlugin(AgentFiles(Path(home.path)))))
        try {
            val agent = agent(harness)
            val session = harness.newSession(agent)
            assertEquals(Support.Unknown, inspect(harness, agent, session.id).tools.single().state.support)
            assertFalse(marker.exists())
            harness.runner(agent, session.id).run(session.id, "hi").toList()
            assertEquals(listOf(McpTool.identity(McpServerConfig.Stdio("local", "", instanceId = "shared/local"), "echo").modelAlias()), seen.last())
            val cached = inspect(harness, agent, session.id).tools.single()
            assertEquals(seen.last().single(), cached.id)
            assertEquals(Support.Supported, cached.state.support)
            assertEquals(1, marker.readLines().size)
            harness.configure(agent, session.id, SessionSettings(model = "limited"))
            assertEquals(Support.Unsupported, inspect(harness, agent, session.id).tools.single().state.support)
            harness.runner(agent, session.id).run(session.id, "hi").toList()
            assertEquals(emptyList(), seen.last())
            assertEquals(1, marker.readLines().size)
            home.resolve("mcp.json").writeText("""{"mcpServers":{}}""")
            assertEquals(emptyList(), inspect(harness, agent, session.id).tools)
        } finally { harness.close() }
    }

    @Test
    fun failedMcpContributionStaysUnavailableWithoutRetryDuringInspection() = runBlocking {
        val marker = home.resolve("attempts")
        home.resolve("mcp.json").writeText("""{"mcpServers":{"broken":{"command":"/bin/sh","args":["-c","echo started >> ${marker.path}; exit 3"]}}}""")
        val harness = harness(listOf(McpPlugin(AgentFiles(Path(home.path)))))
        try {
            val agent = agent(harness)
            val session = harness.newSession(agent)
            assertEquals(Support.Unknown, inspect(harness, agent, session.id).tools.single().state.support)
            assertFalse(marker.exists())
            val events = harness.runner(agent, session.id).run(session.id, "hi").toList()
            assertTrue(events.any { it is AgentEvent.Warning && "MCP server 'broken' unavailable" in it.message })
            assertEquals(emptyList(), seen.last())
            assertEquals(Availability.Unavailable, inspect(harness, agent, session.id).tools.single().state.availability)
            assertEquals(1, marker.readLines().size)
        } finally { harness.close() }
    }

    @Test
    fun mcpInspectionDoesNotStartCommandsResolveSecretsOrRequestSignIn() = runBlocking {
        val marker = home.resolve("started")
        home.resolve("mcp.json").writeText("""{"mcpServers":{"test":{"command":"/bin/sh","args":["-c","touch ${marker.path}"],"env":{"KEY":"${'$'}{pass://Vault/Item/field}"}},"remote":{"type":"http","url":"https://example.invalid/mcp"}}}""")
        var secretReads = 0
        val secrets = Secrets(listOf(object : SecretProvider {
            override val scheme = "pass"
            override val name = "Test"
            override suspend fun status() = error("Must not inspect secrets")
            override suspend fun connect(token: String) = error("Must not connect")
            override suspend fun disconnect() = Unit
            override suspend fun browse(path: List<String>) = emptyList<SecretEntry>()
            override suspend fun resolve(uri: String, reason: String): String { secretReads++; error("Must not resolve") }
        }))
        val plugin = McpPlugin(AgentFiles(Path(home.path)), secrets)
        val harness = harness(listOf(plugin))
        try {
            val agent = agent(harness)
            val tools = inspect(harness, agent).tools
            assertEquals(setOf("test", "remote"), tools.map { it.target }.toSet())
            assertTrue(tools.all { it.state.support == Support.Unknown })
            assertFalse(marker.exists())
            assertEquals(0, secretReads)
            assertEquals(0, modelsBuilt)
            home.resolve("mcp.json").writeText("""{"mcpServers":{}}""")
            assertEquals(emptyList(), inspect(harness, agent).tools)
        } finally { harness.close() }
    }

    @Test
    fun offersWhatItsPluginsCanSetUp() = runBlocking {
        val files = AgentFiles(Path(home.path))
        val harness = harness(listOf(ExecPlugin(files), McpPlugin(files), ExecutorPlugin()))
        assertTrue(harness.integrations[Integration.Tools].orEmpty().containsAll(listOf("run_code", "memory", "computer")))
        assertFalse("run_code" in harness(emptyList()).integrations[Integration.Tools].orEmpty())
        assertEquals(setOf("host", "container", "ssh"), harness.integrations[Integration.Exec])
        assertEquals(setOf("http", "stdio"), harness.integrations[Integration.Mcp])
        assertEquals(emptySet(), harness.integrations[Integration.Secrets])
        val caps = ServerCapabilities("s", integrations = harness.integrations.mapKeys { it.key.name } + ("Future" to setOf("x")))
        assertTrue(caps.offers(Integration.Exec, "ssh") && !caps.offers(Integration.Secrets))
        assertTrue((null as ServerCapabilities?).offers(Integration.Secrets), "A Server that doesn't say allows everything")
    }

    @Test
    fun manyCataloguedToolsReachTheModelOnlyThroughTheCatalog() = runBlocking {
        val many = object : HarnessPlugin {
            override val definition = PluginDefinition("test.many", "Many")
            override val catalogued = true
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools((1..45).map { i ->
                object : Tool {
                    override val descriptor = ToolDescriptor("item_$i", "Item number $i")
                    override suspend fun execute(args: JsonObject) = "item $i"
                }
            })
        }
        val harness = harness(listOf(many))
        try {
            val agent = agent(harness, listOf("memory"))
            val session = harness.newSession(agent)
            harness.runner(agent, session.id).run(session.id, "hi").toList()
            val sent = seen.last()
            assertEquals(listOf("call_tool", "describe_tool", "find_tools", "memory"), sent.map { it.split("__")[2] }.sorted())
            assertEquals(49, inspect(harness, agent, session.id).tools.size)
        } finally { harness.close() }
    }
}
