package kinuvo.harness.mcp

import kotlinx.serialization.json.*
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.modelAlias
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class McpConfigTest {
    private val dir = Files.createTempDirectory("mcp").toFile()
    private fun file(name: String, text: String) = File(dir, name).apply { writeText(text) }.let { Path(it.path) }

    @Test
    fun parsesClaudeCodeFormatWithEnvExpansionAndOverrides() = runBlocking {
        val shared = file("shared.json", """
            {"mcpServers": {
              "fs": {"command": "npx", "args": ["-y", "server-fs", "${'$'}{HOME_DIR}"], "env": {"TOKEN": "${'$'}{TOKEN:-none}"}},
              "api": {"type": "http", "url": "https://x/mcp", "headers": {"Authorization": "Bearer ${'$'}{API_KEY}"}},
              "old": {"type": "sse", "url": "https://x/sse"},
              "broken": {"type": "http", "url": "${'$'}{MISSING}"}
            }}""")
        val agent = file("agent.json", """{"mcpServers": {"fs": {"command": "fs-local", "excludeTools": ["delete"]}}}""")

        val (servers, errors) = McpConfig.load(listOf(shared, agent, Path(dir.path, "absent.json"))) { _, v -> Secrets().expand(v, mapOf("HOME_DIR" to "/h", "API_KEY" to "k"), "test") }

        val byName = servers.associateBy { it.name }
        assertEquals(McpServerConfig.Stdio("fs", "fs-local", excludeTools = listOf("delete")), byName["fs"])
        assertEquals(McpServerConfig.Http("api", "https://x/mcp", mapOf("Authorization" to "Bearer k")), byName["api"])
        assertEquals(setOf("fs", "api"), byName.keys)
        assertTrue(errors.any { "old" in it && "unsupported type 'sse'" in it }, "$errors")
        assertTrue(errors.any { "broken" in it && "MISSING" in it }, "$errors")
        assertTrue(!byName.getValue("fs").allows("delete") && byName.getValue("fs").allows("read"))
    }

    @Test
    fun envDefaultsAndStdioInference() = runBlocking {
        val cfg = McpConfig.parse("s", Json.parseToJsonElement("""{"command":"run","env":{"A":"${'$'}{A:-dflt}"}}""").jsonObject) { Secrets().expand(it, emptyMap(), "test") }
        assertEquals(McpServerConfig.Stdio("s", "run", env = mapOf("A" to "dflt")), cfg)
    }

    @Test
    fun secretsStayOutOfArguments() = runBlocking {
        val error = runCatching { McpConfig.parse("s", Json.parseToJsonElement("""{"command":"run","args":["--token=${'$'}{pass://K/GitHub/token}"]}""").jsonObject) }
        assertTrue("put them in env" in error.exceptionOrNull()!!.message!!)
    }

    @Test fun aliasesAreScopedAndDoNotCollideAfterSanitizingOrTruncation() {
        fun alias(server: String, tool: String) = McpTool.identity(McpServerConfig.Stdio(server, "run"), tool).modelAlias()
        assertNotEquals(alias("my.server", "tool"), alias("my_server", "tool"))
        assertNotEquals(alias("a__b", "c"), alias("a", "b__c"))
        val long = "tool".repeat(30)
        assertNotEquals(alias("server", long + "a"), alias("server", long + "b"))
        assertTrue(alias("server", long).matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }

    @Test fun identitiesDistinguishSharedAndAgentScopedServers() = runBlocking {
        val json = Json.parseToJsonElement("""{"command":"run"}""").jsonObject
        val shared = McpConfig.parse("server", json, "shared")
        val agent = McpConfig.parse("server", json, "agents/kai")
        assertNotEquals(McpTool.identity(shared, "tool"), McpTool.identity(agent, "tool"))
        assertEquals("shared/server", shared.instanceId)
        assertEquals("agents/kai/server", agent.instanceId)
    }

    @Test
    fun approvalOverrideIsParsedAndValidated() = runBlocking {
        val cfg = McpConfig.parse("s", Json.parseToJsonElement("""{"command":"run","approval":"auto"}""").jsonObject)
        assertEquals("auto", cfg.approval)
        val (_, errors) = McpConfig.load(listOf(file("bad.json", """{"mcpServers":{"b":{"command":"x","approval":"maybe"}}}""")))
        assertTrue(errors.single().contains("approval must be auto or ask"))
    }

    @Test fun sharedServersCanBeLimitedToSomeAgents() = runBlocking {
        val shared = file("shared.json", """{"mcpServers":{"work":{"url":"https://w/mcp","agents":["main"]},"all":{"url":"https://a/mcp"}}}""")
        assertEquals(listOf("work", "all"), McpConfig.load(listOf(shared), agentId = "main").first.map { it.name })
        assertEquals(listOf("all"), McpConfig.load(listOf(shared), agentId = "other").first.map { it.name })
        assertEquals(listOf("main"), McpConfig.load(listOf(shared)).first.first().agents)
    }

    @Test fun removingAnAgentDropsItFromSharedServers() = runBlocking {
        val files = kinuvo.harness.core.store.AgentFiles(Path(dir.path))
        val shared = McpFiles(files.sharedMcp, kinuvo.protocol.ConfigScope.Shared)
        shared.save(kinuvo.protocol.McpServerInfo("work", kinuvo.protocol.ConfigScope.Shared, url = "https://w/mcp", agents = listOf("main", "old")))
        McpPlugin(files).mcp.removeAgent("old")
        assertEquals(listOf("main"), shared.get("work")?.agents)
    }
}
