package kinuvo.harness.execution.acp

import kinuvo.harness.execution.api.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class AcpMcpTest {
    private val tools = KinuvoMcp("http://127.0.0.1:8787/mcp", "secret-token", listOf("/usr/bin/kinuvo", "mcp-bridge"))

    /** The `mcpServers` a mock harness received in `session/new`, given the capabilities it advertises. */
    private fun injected(capabilities: String, mcp: KinuvoMcp?): JsonArray = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-acp-mcp").toFile()
        val script = dir.resolve("agent.py")
        val received = dir.resolve("session-new.json")
        script.writeText("""
            import sys,json
            def emit(v): print(json.dumps(v),flush=True)
            for line in sys.stdin:
                r=json.loads(line); m=r.get('method')
                if m=='initialize': emit({'jsonrpc':'2.0','id':r['id'],'result':{'protocolVersion':2,'info':{'name':'mock','version':'1'},'capabilities':$capabilities}})
                elif m=='session/new':
                    open(sys.argv[1],'w').write(json.dumps(r['params'].get('mcpServers',[])))
                    emit({'jsonrpc':'2.0','id':r['id'],'result':{'sessionId':'external'}})
        """.trimIndent())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = ExecutionState("local")
        var session: ExecutionSession? = null
        try {
            val config = HarnessConnection("mock", "Mock", ExecutionConnection.Acp(listOf("/usr/bin/env", "python3", script.path, received.path)))
            session = withTimeout(10_000) {
                AcpExecutionFactory().open(config, AgentRegistration("a", "Agent", "mock", cwd = dir.path), state, ExecutionBroker(state), scope, mcp)
            }
            Json.parseToJsonElement(received.readText()).jsonArray
        } finally {
            session?.close(); scope.cancel(); dir.deleteRecursively()
        }
    }

    @Test fun httpIsInjectedWithTheTokenWhenTheHarnessAdvertisesIt() {
        val server = injected("{'session':{'mcp':{'http':{}}}}", tools).single().jsonObject
        assertEquals("http", server["type"]?.jsonPrimitive?.content)
        assertEquals("http://127.0.0.1:8787/mcp", server["url"]?.jsonPrimitive?.content)
        val header = server["headers"]!!.jsonArray.single().jsonObject
        assertEquals("Bearer secret-token", header["value"]?.jsonPrimitive?.content)
    }

    @Test fun theStdioBridgeIsInjectedOtherwiseWithTheTokenInTheEnvironment() {
        val server = injected("{}", tools).single().jsonObject
        assertEquals("/usr/bin/kinuvo", server["command"]?.jsonPrimitive?.content)
        assertEquals(listOf("mcp-bridge", "--url", "http://127.0.0.1:8787/mcp"), server["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("secret-token" in server["args"].toString())
        assertEquals("secret-token", server["env"]!!.jsonArray.single().jsonObject["value"]?.jsonPrimitive?.content)
    }

    @Test fun nothingIsInjectedWithoutToolsOrAnyWayToReachThem() {
        assertEquals(0, injected("{}", null).size)
        assertEquals(0, injected("{}", tools.copy(bridge = null)).size)
    }
}
