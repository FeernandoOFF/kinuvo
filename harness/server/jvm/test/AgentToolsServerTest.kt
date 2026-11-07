package kinuvo.harness.server

import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

class AgentToolsServerTest {
    @Test fun externalAgentReachesKinuvoToolsOverTheInjectedMcpServerUntilItsExecutionCloses() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-agent-tools").toFile()
        val script = dir.resolve("agent.py")
        val received = dir.resolve("mcp-servers.json")
        script.writeText("""
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': emit({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':r['params']['protocolVersion'],'info':{'name':'mock','version':'1'},'capabilities':{'session':{'mcp':{'http':{}}}}}})
                elif m=='session/new':
                    open(sys.argv[1],'w').write(json.dumps(r['params'].get('mcpServers',[])))
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{'sessionId':'external'}})
        """.trimIndent())
        val server = HarnessServer(Harness(home = Path(dir.path)))
        var client: KinuvoClient? = null
        val http = HttpClient.newHttpClient()
        try {
            val port = server.start("127.0.0.1", 0, schedule = false)
            val connected = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", token = server.auth.sessionToken())
            client = connected; connected.connect()
            connected.call(Api.SaveHarnessConnection, HarnessConnection("mock", "Mock", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.path, received.path))))
            connected.call(Api.RegisterExternalAgent, AgentRegistration("external", "External", "mock", cwd = dir.path))
            val id = connected.call(Api.CreateSession, AgentRef("external")).id
            val injected = withTimeout(10_000) { while (!received.exists() || received.readText().isEmpty()) delay(10); Json.parseToJsonElement(received.readText()).jsonArray.single().jsonObject }
            val url = injected["url"]!!.jsonPrimitive.content
            assertEquals("http://127.0.0.1:$port/mcp", url)
            val bearer = injected["headers"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content

            fun post(authorization: String?, body: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI(url)).apply {
                authorization?.let { header("Authorization", it) }
                header("Content-Type", "application/json")
            }.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            val list = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sessions_list"}}"""

            val ok = withContext(Dispatchers.IO) { post(bearer, list) }
            assertEquals(200, ok.statusCode())
            assertTrue(id in ok.body())
            assertEquals(401, withContext(Dispatchers.IO) { post("Bearer wrong", list) }.statusCode())
            assertEquals(401, withContext(Dispatchers.IO) { post(null, list) }.statusCode())

            connected.call(Api.CloseExecution, ExecutionRef(id))
            assertEquals(401, withContext(Dispatchers.IO) { post(bearer, list) }.statusCode())
        } finally { client?.close(); server.stop(); dir.deleteRecursively() }
    }

    @Test fun mcpIsReachableOnlyFromLoopbackWithoutForwarding() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("kinuvo-mcp-loopback").toFile()
        val server = HarnessServer(Harness(home = Path(dir.path)))
        val http = HttpClient.newHttpClient()
        try {
            val port = server.start("0.0.0.0", 0, schedule = false)
            fun post(host: String, vararg headers: Pair<String, String>) = http.send(HttpRequest.newBuilder(URI("http://$host:$port/mcp")).apply {
                header("Content-Type", "application/json"); headers.forEach { (k, v) -> header(k, v) }
            }.POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
            withContext(Dispatchers.IO) {
                assertEquals(401, post("127.0.0.1"))
                assertEquals(403, post("127.0.0.1", "X-Forwarded-For" to "203.0.113.9"))
                assertEquals(403, post("127.0.0.1", "Origin" to "https://example.com"))
                lanAddresses().firstOrNull()?.let { assertEquals(403, post(it)) }
            }
        } finally { server.stop(); dir.deleteRecursively() }
    }
}
