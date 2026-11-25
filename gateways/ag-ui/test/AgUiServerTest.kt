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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun gatewayTest(origins: Set<String> = emptySet(), block: suspend CoroutineScope.(port: Int) -> Unit) = runBlocking {
        val server = TestServer.start(
            plugins = listOf(FakeTools(FakeTool("deploy", Risk.High, reason = "Pushes to production") { "deployed" })),
            script = { on("alpha") { reply("Alpha reply") }; on("beta") { reply("Beta reply") }; on("please deploy") { toolCall("deploy"); reply("Deployed to production.") }; reply("Hello there") },
            scenario = { agent("work") },
        )
        server.use {
            val client = server.client(token = server.auth.sessionToken()).also { it.connect() }
            val gateway = startAgUi(client, AgentId("work"), "127.0.0.1", 0, allowedOrigins = origins)
            try { withContext(Dispatchers.IO) { block(gateway.port) } } finally { gateway.stop() }
        }
    }

    private fun raw(port: Int, body: String = "{}", contentType: String? = "application/json", origin: String? = null) = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).apply {
            contentType?.let { header("content-type", it) }
            origin?.let { header("origin", it) }
        }.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun errorOf(events: List<AgUiEvent>) = assertIs<AgUiEvent.RunError>(events.last()).message

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

    @Test
    fun aNonJsonContentTypeIsRejected() = gatewayTest { port ->
        assertEquals(415, raw(port, input("c1", "r1", "hi"), contentType = "text/plain").statusCode())
        assertEquals(415, raw(port, input("c1", "r1", "hi"), contentType = null).statusCode())
    }

    @Test
    fun aForeignOriginIsRejectedAndNoCorsHeadersAreSent() = gatewayTest { port ->
        val response = raw(port, input("o1", "r1", "hi"), origin = "https://evil.example")
        assertEquals(403, response.statusCode())
        assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty)
    }

    @Test
    fun aNonLoopbackHostHeaderIsRejected() = gatewayTest { port ->
        val body = input("h1", "r1", "hi")
        val status = Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("POST / HTTP/1.1\r\nHost: evil.example:$port\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
            socket.getInputStream().bufferedReader().readLine()
        }
        assertTrue(status.contains(" 403"), status)
    }

    @Test
    fun anAllowedOriginGetsCorsHeaders() = gatewayTest(origins = setOf("http://localhost:3000")) { port ->
        val response = raw(port, input("o2", "r1", "hi"), origin = "http://localhost:3000")
        assertEquals(200, response.statusCode())
        assertEquals("http://localhost:3000", response.headers().firstValue("access-control-allow-origin").orElse(null))
        assertEquals(403, raw(port, input("o2", "r2", "hi"), origin = "http://localhost:3001").statusCode())
    }

    @Test
    fun aNonLoopbackHostIsRefused() = runBlocking {
        val server = TestServer.start(script = { reply("x") }, scenario = { agent("work") })
        server.use {
            val client = server.client(token = server.auth.sessionToken()).also { it.connect() }
            assertFailsWith<IllegalArgumentException> { startAgUi(client, AgentId("work"), "0.0.0.0", 0) }
        }
    }

    private fun deployInterrupt(port: Int, thread: String): String {
        val first = post(port, input(thread, "r1", "please deploy"))
        return assertIs<RunOutcome.Interrupted>(assertIs<AgUiEvent.RunFinished>(first.last()).outcome).interrupts.single().id
    }

    @Test
    fun anUnknownDecisionIsRejectedNotAllowed() = gatewayTest { port ->
        val id = deployInterrupt(port, "d1")
        listOf("""{"decision":"yes"}""", "{}").forEach { payload ->
            val events = post(port, """{"threadId":"d1","runId":"r2","messages":[],"resume":[{"interruptId":"$id","status":"resolved","payload":$payload}]}""")
            assertTrue(errorOf(events).contains("decision"))
        }
        val allowed = post(port, """{"threadId":"d1","runId":"r3","messages":[],"resume":[{"interruptId":"$id","status":"resolved","payload":{"decision":"allow_once"}}]}""")
        assertTrue(allowed.text().contains("Deployed to production."))
    }

    @Test
    fun aCancelledInterruptIsDenied() = gatewayTest { port ->
        val id = deployInterrupt(port, "d2")
        val events = post(port, """{"threadId":"d2","runId":"r2","messages":[],"resume":[{"interruptId":"$id","status":"cancelled"}]}""")
        assertIs<AgUiEvent.RunFinished>(events.last())
    }

    @Test
    fun aPartialResumeFailsFast() = gatewayTest { port ->
        deployInterrupt(port, "p1")
        val started = System.nanoTime()
        val events = post(port, """{"threadId":"p1","runId":"r2","messages":[],"resume":[{"interruptId":"nope","status":"resolved","payload":{"decision":"allow_once"}}]}""")
        assertTrue(errorOf(events).contains("Unknown interrupts"))
        assertTrue((System.nanoTime() - started) / 1_000_000_000 < 10)
        assertTrue(errorOf(post(port, """{"threadId":"p1","runId":"r3","messages":[],"resume":[]}""")).isNotEmpty())
    }

    @Test
    fun aQueuedMessageStreamsItsOwnRunNotTheResumedOne() = gatewayTest { port ->
        val id = deployInterrupt(port, "q1")
        val queued = async(Dispatchers.IO) { post(port, input("q1", "run-beta", "beta")) }
        delay(500)
        val resumed = post(port, """{"threadId":"q1","runId":"run-resume","messages":[],"resume":[{"interruptId":"$id","status":"resolved","payload":{"decision":"allow_once"}}]}""")
        assertEquals("run-resume", assertIs<AgUiEvent.RunStarted>(resumed.first()).runId)
        assertTrue(resumed.text().contains("Deployed to production."))
        assertEquals(1, resumed.count { it is AgUiEvent.RunStarted })

        val events = queued.await()
        assertEquals("run-beta", assertIs<AgUiEvent.RunStarted>(events.first()).runId)
        assertEquals("Beta reply", events.text())
        assertEquals("run-beta", assertIs<AgUiEvent.RunFinished>(events.last()).runId)
        assertEquals(1, events.count { it is AgUiEvent.RunStarted })
    }

    @Test
    fun aSendDuringARunIsReportedNotAttributedToThatRun() = gatewayTest { port ->
        val results = listOf("alpha", "beta").map { word -> async(Dispatchers.IO) { word to post(port, input("o3", "run-$word", word)) } }.awaitAll()
        results.forEach { (word, events) ->
            assertEquals("run-$word", assertIs<AgUiEvent.RunStarted>(events.first()).runId)
            val reply = "${word.replaceFirstChar { it.uppercase() }} reply"
            assertTrue(events.last() is AgUiEvent.RunError || events.text() == reply, events.toString())
            assertTrue(events.text().isEmpty() || events.text() == reply)
        }
    }
}
