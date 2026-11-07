package kinuvo.harness.core.tools

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentRun
import kinuvo.harness.core.ApprovalBroker
import kinuvo.harness.core.ApprovalPolicy
import kinuvo.harness.core.Approvals
import kinuvo.harness.core.SecretProvider
import kinuvo.harness.core.SecretRequests
import kinuvo.harness.core.SecretScope
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.TestHome
import kinuvo.harness.core.plugin.PluginTools
import kinuvo.harness.core.plugin.ownedBy
import kinuvo.harness.testing.scriptedModel
import kinuvo.harness.testing.toolResults
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.SecretAnswer
import kinuvo.protocol.SecretEntry
import kinuvo.protocol.SecretProviderStatus
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ProtocolJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RequestSecretToolTest {
    private class Manager(var connected: Boolean = true) : SecretProvider {
        override val scheme = "pass"
        override val name = "Proton Pass"
        override suspend fun resolve(uri: String, reason: String) = "ghp_from_manager"
        override suspend fun status() = SecretProviderStatus(scheme, name, installed = true, connected = connected)
        override suspend fun connect(token: String) {}
        override suspend fun disconnect() {}
        override suspend fun browse(path: List<String>) = emptyList<SecretEntry>()
    }

    private val home = TestHome()
    private val manager = Manager()
    private val secrets = Secrets(listOf(manager))
    private val requests = SecretRequests(secrets)
    private val tool = PluginTools(listOf(RequestSecretTool())).ownedBy(PluginInstanceId("kinuvo.core")).tools.single()
    private val typed = "ghp_typed_value_42"

    private data class Outcome(val events: List<AgentEvent>, val output: String, val transcript: String, val session: String)

    private suspend fun TestScope.ask(answer: (String) -> SecretAnswer): Outcome {
        val model = scriptedModel { prompt, _ ->
            if (prompt.messages.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("c1", tool.descriptor.name, """{"label":"GitHub token","reason":"push a branch"}"""), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("thanks"), StreamFrame.End())
        }
        val agent = home.agent.copy(config = home.agent.config.copy(approvals = "strict"))
        val session = home.store.createSession(agent.id, "").id
        val runner = AgentRun(agent, home.store, model, listOf(tool), approvals = Approvals(ApprovalPolicy(agent, home.trustRules), ApprovalBroker()), secretRequests = requests)
        val events = mutableListOf<AgentEvent>()
        val asked = Channel<AgentEvent.SecretRequested>(Channel.UNLIMITED)
        val job = launch { runner.run(session, "push it").collect { events += it; if (it is AgentEvent.SecretRequested) asked.send(it) } }
        val request = asked.receive()
        assertEquals("GitHub token", request.label)
        assertEquals("push a branch", request.reason)
        assertTrue(requests.respond(answer(request.requestId)))
        job.join()
        val entries = home.store.entries(agent.id, session)
        return Outcome(events, entries.filterIsInstance<SessionEntry.ToolResult>().single().output, entries.joinToString { ProtocolJson.encodeToString(SessionEntry.serializer(), it) }, session)
    }

    @Test
    fun aPickedItemComesBackAsItsReferenceOnly() = runTest {
        val picked = "\${pass://Kinuvo/GitHub/token}"
        val outcome = ask { SecretAnswer(it, reference = picked) }
        assertTrue(outcome.output.startsWith("The user shared GitHub token as $picked."), outcome.output)
        assertTrue("MCP server" in outcome.output)
        assertFalse("ghp_from_manager" in outcome.transcript)
        assertTrue(outcome.events.any { it is AgentEvent.SecretResolved })
    }

    @Test
    fun aTypedValueStaysInMemoryForTheAskingSessionOnly() = runTest {
        val outcome = ask { SecretAnswer(it, value = typed) }
        val reference = Regex("""\$\{secret://[^}]+}""").find(outcome.output)!!.value
        assertTrue(outcome.output.startsWith("The user shared GitHub token as $reference."), outcome.output)
        assertTrue("can't go in an MCP server" in outcome.output)
        listOf(outcome.output, outcome.transcript, outcome.events.joinToString { it.toString() }, ProtocolJson.encodeToString(AgentEvent.serializer(), outcome.events.first { it is AgentEvent.SecretResolved }))
            .forEach { assertFalse(typed in it, it) }
        assertTrue(outcome.events.any { it is AgentEvent.SecretResolved })

        val uri = reference.removeSurrounding("\${", "}")
        val owner = SecretScope(home.agent.id, outcome.session)
        assertEquals(typed, secrets.resolve(uri, "test", owner))
        assertEquals(typed, secrets.expand(reference, emptyMap(), "test", owner))
        assertFailsWith<IllegalStateException> { secrets.resolve(uri, "test", SecretScope(home.agent.id, "another")) }
        assertFailsWith<IllegalStateException> { secrets.resolve(uri, "test", SecretScope("other-agent", outcome.session)) }
        assertFailsWith<IllegalStateException> { secrets.resolve(uri, "test") }
        assertFailsWith<IllegalStateException> { secrets.expand("token \${$uri}", emptyMap(), "test") }
    }

    @Test
    fun typedValuesAreAlwaysMasked() = runTest {
        val outcome = ask { SecretAnswer(it, value = typed) }
        assertEquals("echo ••••", secrets.redact("echo $typed"))
        val echo = object : Tool by tool {
            override suspend fun execute(args: kotlinx.serialization.json.JsonObject) = "value is $typed"
        }
        assertEquals("value is ••••", secrets.redacting(echo).execute(kotlinx.serialization.json.JsonObject(emptyMap())))
        assertNotEquals("", outcome.session)
    }

    @Test
    fun declining() = runTest {
        val outcome = ask { SecretAnswer(it) }
        assertEquals("The user declined to share it. Don't ask for it in chat.", outcome.output)
        assertTrue(outcome.events.any { it is AgentEvent.SecretResolved })
    }

    @Test
    fun anUnansweredRequestIsSkippedWhenTheRunOnlyWaitsSoLong() = runTest {
        val model = scriptedModel { prompt, _ ->
            if (prompt.messages.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("c1", tool.descriptor.name, """{"label":"GitHub token"}"""), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("ok"), StreamFrame.End())
        }
        val agent = home.agent
        val session = home.store.createSession(agent.id, "").id
        val skipped = mutableListOf<String>()
        val approvals = Approvals(ApprovalPolicy(agent, home.trustRules), ApprovalBroker(), wait = 30.minutes, skipped = { skipped += it })
        val job = launch { AgentRun(agent, home.store, model, listOf(tool), approvals = approvals, secretRequests = requests).run(session, "go").collect {} }
        testScheduler.advanceTimeBy(31.minutes)
        job.join()
        assertEquals("No answer within 30 minutes; skipped", home.store.entries(agent.id, session).filterIsInstance<SessionEntry.ToolResult>().single().output)
        assertEquals(listOf("request_secret"), skipped)
    }

    @Test
    fun theAnswerIsChecked() = runTest {
        val pending = requests.register(SecretScope("a", "s"))
        suspend fun fails(answer: SecretAnswer) = assertFailsWith<IllegalArgumentException> { requests.respond(answer) }
        fails(SecretAnswer(pending.id, reference = "ghp_plain"))
        fails(SecretAnswer(pending.id, reference = "\${HOME}"))
        fails(SecretAnswer(pending.id, reference = "\${secret://other}"))
        fails(SecretAnswer(pending.id, reference = "\${pass://V/I/f}", value = "x"))
        fails(SecretAnswer(pending.id, value = ""))
        assertFailsWith<IllegalStateException> { requests.respond(SecretAnswer(pending.id, reference = "\${op://V/I/f}")) }
        manager.connected = false
        val e = fails(SecretAnswer(pending.id, reference = "\${pass://V/I/f}"))
        assertTrue("isn't connected" in e.message!!, e.message)
        manager.connected = true
        assertTrue(requests.respond(SecretAnswer(pending.id, reference = "\${pass://V/I/f}")))
        assertEquals("\${pass://V/I/f}", pending.await())
        assertFalse(requests.respond(SecretAnswer(pending.id)))
        pending.close()
        assertFalse(requests.respond(SecretAnswer("missing")))
    }

    @Test
    fun typedValuesExpire() = runTest {
        val time = kotlin.time.TestTimeSource()
        val short = Secrets(time = time)
        val scope = SecretScope("a", "s")
        val uri = short.session.store(scope, typed)
        assertEquals(typed, short.resolve(uri, "t", scope))
        time += kotlin.time.Duration.parse("25h")
        assertFailsWith<IllegalStateException> { short.resolve(uri, "t", scope) }
        assertEquals("echo $typed", short.redact("echo $typed"))
    }
}

