package kinuvo.server.tools

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId
import kinuvo.server.SecretRequests
import kinuvo.server.Secrets
import kinuvo.server.plugin.ToolKind
import kinuvo.testing.server.FakeSecretProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestSecretToolTest {
    private val agent = AgentId("a")
    private val session = SessionId("s")
    private val pass = FakeSecretProvider(mapOf(listOf("Work", "GitHub", "token") to "ghp_private"))
    private val secrets = Secrets(listOf(pass, FakeSecretProvider(emptyMap(), scheme = "op", connected = false)))
    private val requests = SecretRequests(secrets)

    @Test fun needsALabelAndAReason() = runTest {
        val tool = RequestSecretTool()
        assertEquals(ToolKind.SecretRequest, tool.kind)
        tool.bind(buildJsonObject { put("label", "API token"); put("reason", "Publish") })()
        assertFailsWith<IllegalArgumentException> { tool.bind(buildJsonObject { put("label", " "); put("reason", "Publish") }) }
        assertFailsWith<IllegalArgumentException> { tool.bind(buildJsonObject { put("label", "API token") }) }
    }

    @Test fun answersNeverPrintTheirValue() {
        assertFalse("private-value" in InteractionAnswer.Secret(value = "private-value").toString())
    }

    private suspend fun TestScope.ask(answer: InteractionAnswer.Secret): String {
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val result = async { requests.ask(agent, session, ToolCallId("c1"), "GitHub token", "Publish the release", events::send) }
        val asked = assertIs<PendingInteraction.Secret>(assertIs<AgentEvent.InteractionRequested>(events.receive()).interaction)
        assertEquals("GitHub token", asked.label)
        assertTrue(requests.respond(asked.id, answer))
        assertFalse(requests.respond(asked.id, answer))
        return result.await().also { assertIs<AgentEvent.InteractionResolved>(events.receive()) }
    }

    @Test fun aTypedValueBecomesAReferenceForThisAgentAndSession() = runTest {
        val reference = ask(InteractionAnswer.Secret(value = "typed-private"))
        assertTrue(reference.startsWith("\${secret://"))
        assertEquals("typed-private", secrets.scoped(agent, session).expand(reference, emptyMap(), "test"))
    }

    @Test fun aPickedFieldIsReturnedAsItsReference() = runTest {
        assertEquals("\${pass://Work/GitHub/token}", ask(InteractionAnswer.Secret(reference = "\${pass://Work/GitHub/token}")))
        assertTrue(pass.reads.isEmpty())
    }

    @Test fun aDeclineTellsTheAgentNotToAskInChat() = runTest {
        assertTrue("Don't ask for this credential in chat" in ask(InteractionAnswer.Secret()))
    }

    @Test fun refusesAnswersThatArentAPrivatePickOrValue() = runTest {
        val id = InteractionId("x")
        assertFailsWith<IllegalArgumentException> { requests.respond(id, InteractionAnswer.Secret(reference = "\${pass://a/b/c}", value = "v")) }
        assertFailsWith<IllegalArgumentException> { requests.respond(id, InteractionAnswer.Secret(value = "")) }
        assertFailsWith<IllegalArgumentException> { requests.respond(id, InteractionAnswer.Secret(reference = "\${op://a/b/c}")) }
        assertFailsWith<IllegalArgumentException> { requests.respond(id, InteractionAnswer.Secret(reference = "\${secret://someone-else}")) }
        assertFailsWith<IllegalArgumentException> { requests.respond(id, InteractionAnswer.Secret(reference = "plain text")) }
        assertFalse(requests.respond(id, InteractionAnswer.Secret()))
    }
}
