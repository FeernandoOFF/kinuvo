package kinuvo.plugins.mcp

import kotlin.test.AfterTest
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpServerTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    @Test
    fun failingStdioServerWarningIncludesStderrAndExitCode() = runBlocking {
        val cfg = File(temporaryDirectories.create("mcp").toFile(), "mcp.json").apply {
            writeText("""{"mcpServers": {"bad": {"command": "sh", "args": ["-c", "echo boom-details >&2; exit 3"]}}}""")
        }
        val manager = McpManager(listOf(Path(cfg.path)))
        val (tools, warnings) = manager.tools()
        manager.close()
        assertTrue(tools.isEmpty())
        val w = warnings.single()
        assertTrue("MCP server 'bad' unavailable" in w && "boom-details" in w && "exit code 3" in w, w)
    }

    /** Accepts TCP connections but never answers, like a server stuck behind a broken proxy or waiting on sign-in. */
    private class HangingServer {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val accepted = AtomicInteger(0)
        private val acceptor = thread(isDaemon = true) {
            runCatching { while (true) { socket.accept(); accepted.incrementAndGet() } }
        }
        fun close() { socket.close() }
    }

    @Test
    fun hangingHttpServerTimesOutThenBacksOffInsteadOfRetryingEveryCall() = runBlocking {
        val hung = HangingServer()
        val cfg = McpServerConfig.Http("slow", "http://127.0.0.1:${hung.port}/mcp")
        try {
            // Warms up client class loading, which on a loaded runner outlasts the timeout and isn't what is under test.
            McpServer(cfg, connectTimeoutMillis = 200, backoffSeconds = 30).also { runCatching { it.listTools() }; it.close() }
            val server = McpServer(cfg, connectTimeoutMillis = 200, backoffSeconds = 30)
            try {
                val first = assertFailsWith<IllegalStateException> { server.listTools() }
                assertTrue("didn't respond within 200ms" in first.message.orEmpty(), "the connect timeout must end the attempt: ${first.message}")
                val acceptedAfterFirst = hung.accepted.get()

                val second = assertFailsWith<IllegalStateException> { server.listTools() }
                assertSame(first, second, "a server that just failed must short-circuit with its cached failure instead of reconnecting")
                assertEquals(acceptedAfterFirst, hung.accepted.get(), "the cached failure must not open a new connection")
            } finally {
                server.close()
            }
        } finally {
            hung.close()
        }
    }

    @Test
    fun workingStdioServerConnectionIsReusedAcrossToolsCalls() = runBlocking {
        val dir = temporaryDirectories.create("mcp-reuse").toFile()
        val script = File(dir, "server.py").apply { writeText(PING_SERVER) }
        val initCount = File(dir, "init.count")
        val cfg = File(dir, "mcp.json").apply {
            writeText("""{"mcpServers": {"ok": {"command": "python3", "args": ["${script.path}", "${initCount.path}"]}}}""")
        }
        val manager = McpManager(listOf(Path(cfg.path)))
        try {
            val (first, firstWarnings) = manager.tools()
            assertEquals(emptyList(), firstWarnings)
            assertEquals(listOf("ping"), first.map { it.displayName })

            val (second, secondWarnings) = manager.tools()
            assertEquals(emptyList(), secondWarnings)
            assertEquals(listOf("ping"), second.map { it.displayName })
            assertEquals("1", initCount.readText(), "an already-connected server must be reused, not reconnected, on the next run")
        } finally {
            manager.close()
        }
    }

    private companion object {
        val PING_SERVER = """
import json, sys

init = sys.argv[1]
count = 0
def reply(i, result): print(json.dumps({"jsonrpc": "2.0", "id": i, "result": result}), flush=True)
for line in sys.stdin:
    m = json.loads(line)
    method, i, params = m.get("method"), m.get("id"), m.get("params") or {}
    if method == "initialize":
        count += 1
        open(init, "w").write(str(count))
        reply(i, {"protocolVersion": params["protocolVersion"], "capabilities": {"tools": {}}, "serverInfo": {"name": "ok", "version": "1"}})
    elif method == "tools/list":
        schema = {"type": "object", "properties": {}}
        reply(i, {"tools": [{"name": "ping", "inputSchema": schema}]})
    elif i is not None:
        print(json.dumps({"jsonrpc": "2.0", "id": i, "error": {"code": -32601, "message": "nope"}}), flush=True)
"""
    }
}
