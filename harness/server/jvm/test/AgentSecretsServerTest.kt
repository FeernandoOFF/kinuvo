package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentModel
import kinuvo.harness.core.SecretProvider
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.core.plugin.HarnessPlugin
import kinuvo.harness.core.plugin.ModelProvider
import kinuvo.harness.testing.scriptedModel
import kinuvo.harness.testing.toolResults
import kinuvo.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentSecretsServerTest {
    private val dir: File = Files.createTempDirectory("kinuvo-secrets").toFile()
    private val typed = "ghp_typed_value_42"
    private val advertised = mutableMapOf<String, List<String>>()
    private var connected = true

    private val manager = object : SecretProvider {
        override val scheme = "pass"
        override val name = "Proton Pass"
        override suspend fun resolve(uri: String, reason: String) = "ghp_from_manager"
        override suspend fun status() = SecretProviderStatus(scheme, name, installed = true, connected = connected)
        override suspend fun connect(token: String) {}
        override suspend fun disconnect() {}
        override suspend fun browse(path: List<String>) = emptyList<SecretEntry>()
    }

    private fun provider(name: String, local: Boolean) = object : HarnessPlugin, ModelProvider {
        override val definition = PluginDefinition("test.$name", "Test")
        override val id = name
        override val modelProviders get() = listOf(this)
        override fun onDevice(model: String) = local
        override fun model(config: AgentConfig): AgentModel = scriptedModel { prompt, tools ->
            advertised["${config.provider}/${config.model}"] = tools.map { it.name }
            if (prompt.messages.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("c1", "request_secret", """{"label":"GitHub token","reason":"push"}"""), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
        }
    }

    private val harness = Harness(Path(dir.path), plugins = listOf(provider("cloud", false), provider("local", true)), secretProviders = listOf(manager))
    private val server = HarnessServer(harness)
    private val admin get() = Admin(harness, Runs(harness.log, CoroutineScope(Dispatchers.Default))) {}
    private val port get() = server.runtime.mcp.url!!.substringAfterLast(':').substringBefore('/')

    private suspend fun until(check: suspend () -> Boolean) = withTimeout(10_000) { while (!check()) delay(20) }

    private fun test(block: suspend KinuvoClient.() -> Unit) = try {
        runBlocking {
            server.start("127.0.0.1", 0, schedule = false)
            for ((id, provider) in listOf("default" to "cloud", "private" to "local")) {
                if (harness.store.agents().none { it.id == id }) harness.store.createAgent(id)
                admin.update(UpdateAgentParams(id, provider = provider, model = "m"))
            }
            val client = KinuvoClient("ws://127.0.0.1:$port/ws", "secrets", token = server.auth.sessionToken())
            try { client.connect(); client.block() } finally { client.close() }
        }
    } finally { runBlocking { server.stop() }; dir.deleteRecursively() }

    private suspend fun KinuvoClient.answering(agentId: String, sessionId: String, answer: (String) -> SecretAnswer): String {
        call(Api.Send, SendParams(agentId, sessionId, "go"))
        until { harness.log.live(sessionId).secret != null }
        val request = harness.log.live(sessionId).secret!!
        assertEquals("GitHub token", request.label)
        assertEquals(SessionStatus.Waiting, harness.log.status(sessionId))
        assertTrue(call(Api.RespondSecret, answer(request.requestId)))
        until { harness.log.status(sessionId) == SessionStatus.Idle }
        assertNull(harness.log.live(sessionId).secret)
        return harness.store.entries(agentId, sessionId).filterIsInstance<SessionEntry.ToolResult>().single().output
    }

    private suspend fun sessionOf(agentId: String) = harness.newSession(harness.store.agents().first { it.id == agentId }).id

    private fun everywhere(sessionId: String): String = runBlocking {
        val entries = harness.store.entries("default", sessionId).joinToString { ProtocolJson.encodeToString(SessionEntry.serializer(), it) }
        val live = ProtocolJson.encodeToString(LiveState.serializer(), harness.log.live(sessionId))
        val files = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        entries + live + files
    }

    @Test fun aTypedValueReachesTheAgentOnlyAsAReferenceAndIsNeverStored() = test {
        val session = sessionOf("default")
        val output = answering("default", session) { SecretAnswer(it, value = typed) }
        assertTrue(Regex("""The user shared GitHub token as \$\{secret://[^}]+}\.""").containsMatchIn(output), output)
        assertFalse(typed in output)
        assertFalse(typed in everywhere(session))
        val uri = Regex("""\$\{(secret://[^}]+)}""").find(output)!!.groupValues[1]
        assertEquals(typed, harness.secrets.resolve(uri, "test", kinuvo.harness.core.SecretScope("default", session)))
        assertFailsWith<IllegalStateException> { harness.secrets.resolve(uri, "test", kinuvo.harness.core.SecretScope("default", sessionOf("default"))) }
    }

    @Test fun aPickedItemComesBackAsItsReference() = test {
        val session = sessionOf("default")
        val output = answering("default", session) { SecretAnswer(it, reference = "\${pass://Kinuvo/GitHub/token}") }
        assertTrue("shared GitHub token as \${pass://Kinuvo/GitHub/token}." in output, output)
        assertFalse("ghp_from_manager" in everywhere(session))
    }

    @Test fun decliningTellsTheAgentNotToAskInChat() = test {
        val output = answering("default", sessionOf("default")) { SecretAnswer(it) }
        assertEquals("The user declined to share it. Don't ask for it in chat.", output)
    }

    @Test fun theServerChecksTheReferenceAndKeepsWaitingAfterABadAnswer() = test {
        val session = sessionOf("default")
        call(Api.Send, SendParams("default", session, "go"))
        until { harness.log.live(session).secret != null }
        val id = harness.log.live(session).secret!!.requestId
        for (bad in listOf(SecretAnswer(id, reference = "ghp_plain"), SecretAnswer(id, reference = "\${secret://x}"), SecretAnswer(id, reference = "\${op://V/I/f}"), SecretAnswer(id, reference = "\${pass://V/I/f}", value = typed))) {
            assertFails { call(Api.RespondSecret, bad) }
        }
        connected = false
        assertTrue(assertFails { call(Api.RespondSecret, SecretAnswer(id, reference = "\${pass://V/I/f}")) }.message!!.contains("isn't connected"))
        assertNotNull(harness.log.live(session).secret)
        assertFalse(call(Api.RespondSecret, SecretAnswer("missing", value = typed)))
        call(Api.RespondSecret, SecretAnswer(id))
        until { harness.log.status(session) == SessionStatus.Idle }
    }

    @Test fun aHiddenSessionCanAskToo() = test {
        val session = sessionOf("private").also { harness.store.append("private", it, SessionEntry.Details(hidden = true)) }
        val output = answering("private", session) { SecretAnswer(it, value = typed) }
        assertTrue("secret://" in output)
        assertTrue("request_secret" in advertised.getValue("local/m"))
        assertFalse(typed in ProtocolJson.encodeToString(LiveState.serializer(), harness.log.live(session)))
    }

    @Test fun theToolIsServedOverMcpAndAnswersWithOnlyAReference() = test {
        suspend fun rpc(token: String, method: String, params: JsonObject) = Json.parseToJsonElement(
            server.runtime.mcpEndpoint.handle("Bearer $token", buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString()).body!!).jsonObject["result"]!!.jsonObject
        val external = server.runtime.mcp.grant("external-agent", "external-session")!!.token
        assertTrue(rpc(external, "tools/list", JsonObject(emptyMap()))["tools"]!!.jsonArray.any { it.jsonObject["name"]!!.jsonPrimitive.content == "request_secret" })

        val session = sessionOf("default")
        val token = server.runtime.mcp.grant("default", session)!!.token
        harness.log.publish("default", session, AgentEvent.RunStarted("external-run"))
        val reply = CoroutineScope(Dispatchers.Default).async {
            rpc(token, "tools/call", buildJsonObject { put("name", "request_secret"); put("arguments", buildJsonObject { put("label", "GitHub token") }) })
        }
        until { harness.log.live(session).secret != null || reply.isCompleted }
        if (reply.isCompleted) fail(reply.await().toString())
        assertTrue(call(Api.RespondSecret, SecretAnswer(harness.log.live(session).secret!!.requestId, value = typed)))
        val text = withTimeout(10_000) { reply.await() }.toString()
        assertTrue("secret://" in text && typed !in text, text)
        until { harness.log.live(session).secret == null }
        harness.log.publish("default", session, AgentEvent.RunFinished("external-run"))
        assertFalse(typed in everywhere(session))
    }
}
