package kinuvo.gateways.agui

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agui.AgUiEvent
import kinuvo.protocol.agui.RunOutcome
import kinuvo.protocol.approval.Risk
import kinuvo.testing.server.FakeTool
import kinuvo.testing.server.FakeTools
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgUiServerTest {
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(requireNotNull(javaClass.getResourceAsStream("/agui/schema.json")).readBytes().decodeToString())

    private fun post(port: Int, body: String): List<AgUiEvent> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"))
        return response.body().split("\n\n").filter { it.startsWith("data: ") }.map { frame ->
            val json = frame.removePrefix("data: ")
            val errors = schema.validate(json, InputFormat.JSON)
            assertTrue(errors.isEmpty(), "$json\n${errors.joinToString("\n")}")
            ProtocolJson.decodeFromString(AgUiEvent.serializer(), json)
        }
    }

    private fun input(thread: String, run: String, text: String) =
        """{"threadId":"$thread","runId":"$run","messages":[{"id":"m","role":"user","content":"$text"}],"tools":[],"context":[],"state":{},"forwardedProps":{}}"""

    private fun gatewayTest(block: suspend (port: Int) -> Unit) = runBlocking {
        val server = TestServer.start(
            plugins = listOf(FakeTools(FakeTool("deploy", Risk.High, reason = "Pushes to production") { "deployed" })),
            script = { on("please deploy") { toolCall("deploy"); reply("Deployed to production.") }; reply("Hello there") },
            scenario = { agent("work") },
        )
        server.use {
            val client = server.client(token = server.auth.sessionToken()).also { it.connect() }
            val gateway = startAgUi(client, AgentId("work"), "127.0.0.1", 0)
            try { withContext(Dispatchers.IO) { block(gateway.port) } } finally { gateway.stop() }
        }
    }

    private fun List<AgUiEvent>.text() = filterIsInstance<AgUiEvent.TextMessageContent>().joinToString("") { it.delta }

    @Test
    fun aMessageStreamsARunWithTheClientsIds() = gatewayTest { port ->
        val events = post(port, input("t1", "r1", "hi"))
        assertEquals(AgUiEvent.RunStarted("t1", "r1"), events.first().let { (it as AgUiEvent.RunStarted).copy(metadata = null) })
        assertTrue(events.text().contains("Hello there"))
        val finished = assertIs<AgUiEvent.RunFinished>(events.last())
        assertEquals("t1", finished.threadId)
        assertEquals("r1", finished.runId)
        assertEquals(RunOutcome.Success(), finished.outcome)
    }

    @Test
    fun anInterruptIsResumedIntoANewRun() = gatewayTest { port ->
        val first = post(port, input("t2", "r1", "please deploy"))
        val outcome = assertIs<RunOutcome.Interrupted>(assertIs<AgUiEvent.RunFinished>(first.last()).outcome)
        val interrupt = outcome.interrupts.single()
        assertEquals("tool_call", interrupt.reason)

        val second = post(port, """{"threadId":"t2","runId":"r2","messages":[],"resume":[{"interruptId":"${interrupt.id}","status":"resolved","payload":{"decision":"allow_once"}}]}""")
        assertEquals("r2", assertIs<AgUiEvent.RunStarted>(second.first()).runId)
        assertTrue(second.text().contains("Deployed to production."))
        assertEquals(RunOutcome.Success(), assertIs<AgUiEvent.RunFinished>(second.last()).outcome)
    }
}
