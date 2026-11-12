package kinuvo.plugins.mcp

import kinuvo.harness.ToolSpec
import kinuvo.server.plugin.ToolRun
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class McpAppTest {
    private val run = object : ToolRun {
        override val tools = emptyList<ToolSpec>()
        override suspend fun call(name: String, args: String) = SessionEntry.ToolResult(name, name, "", isError = true)
    }

    @Test
    fun appToolsCarryTheirResourceAndAppOnlyToolsStayHiddenFromTheModel(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-app").toFile()
        val script = File(dir, "server.py").apply { writeText(SERVER) }
        val cfg = File(dir, "mcp.json").apply {
            writeText("""{"mcpServers": {"weather": {"command": "python3", "args": ["${script.path}", "${File(dir, "init.json").path}"]}}}""")
        }
        val manager = McpManager(listOf(Path(cfg.path)))
        try {
            val (tools, warnings) = manager.tools()
            assertEquals(emptyList(), warnings)
            assertEquals(listOf("show", "model_only"), tools.map { it.displayName })
            val capabilities = Json.parseToJsonElement(File(dir, "init.json").readText()).jsonObject
            assertEquals("text/html;profile=mcp-app", capabilities["extensions"]!!.jsonObject["io.modelcontextprotocol/ui"]!!.jsonObject["mimeTypes"]!!.jsonArray.single().jsonPrimitive.content)

            val result = tools.first().executeResult(buildJsonObject { put("city", "Dublin") }, "call", run)
            val ref = assertNotNull(result.app)
            assertEquals("ui://weather/app.html", ref.resourceUri)
            assertEquals(12, result.appResult!!["structuredContent"]!!.jsonObject["temp"]!!.jsonPrimitive.int)

            val resource = manager.resource(ref)
            assertTrue("<h1>Weather</h1>" in resource.html)
            assertEquals("https://api.example.com", resource.ui["csp"]!!.jsonObject["connectDomains"]!!.jsonArray.single().jsonPrimitive.content)

            assertEquals(resource, manager.resource(ref))
            assertEquals("1", File(dir, "init.json.reads").readText())
            assertFailsWith<IllegalStateException> { manager.appTool(ref, "model_only") }
            assertFailsWith<IllegalStateException> { manager.appTool(ref.copy(serverId = "another-server"), "refresh") }
            assertFailsWith<IllegalStateException> { manager.resource(ref.copy(hash = "changed")) }

            val refresh = manager.appTool(ref, "refresh").executeResult(JsonObject(emptyMap()), "app", run)
            assertTrue(refresh.output.startsWith("refreshed"))
            assertFailsWith<IllegalStateException> { manager.appTool(ref, "missing") }

            cfg.writeText("""{"mcpServers": {}}""")
            assertFailsWith<IllegalStateException> { manager.appTool(ref, "refresh") }
        } finally {
            manager.close()
        }
    }

    private companion object {
        val SERVER = """
import json, sys

init = sys.argv[1]
reads = 0
def reply(i, result): print(json.dumps({"jsonrpc": "2.0", "id": i, "result": result}), flush=True)
for line in sys.stdin:
    m = json.loads(line)
    method, i, params = m.get("method"), m.get("id"), m.get("params") or {}
    if method == "initialize":
        open(init, "w").write(json.dumps(params.get("capabilities")))
        reply(i, {"protocolVersion": params["protocolVersion"], "capabilities": {"tools": {}, "resources": {}}, "serverInfo": {"name": "weather", "version": "1"}})
    elif method == "tools/list":
        schema = {"type": "object", "properties": {}}
        reply(i, {"tools": [
            {"name": "show", "inputSchema": schema, "_meta": {"ui": {"resourceUri": "ui://weather/app.html"}}},
            {"name": "model_only", "inputSchema": schema, "_meta": {"ui": {"visibility": ["model"]}}},
            {"name": "refresh", "inputSchema": schema, "_meta": {"ui": {"resourceUri": "ui://weather/app.html", "visibility": ["app"]}}},
        ]})
    elif method == "resources/read":
        reads += 1
        open(init + ".reads", "w").write(str(reads))
        reply(i, {"contents": [{"uri": params["uri"], "mimeType": "text/html;profile=mcp-app", "text": "<h1>Weather</h1>",
            "_meta": {"ui": {"csp": {"connectDomains": ["https://api.example.com"]}}}}]})
    elif method == "tools/call":
        if params["name"] == "show":
            reply(i, {"content": [{"type": "text", "text": "12C"}], "structuredContent": {"temp": 12}})
        else:
            reply(i, {"content": [{"type": "text", "text": "refreshed"}]})
    elif i is not None:
        print(json.dumps({"jsonrpc": "2.0", "id": i, "error": {"code": -32601, "message": "nope"}}), flush=True)
"""
    }
}
