package kinuvo.server

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.server.plugin.ToolRun
import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.CoreTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class SessionSecretsTest {
    private val agent = AgentId("a")
    private val session = SessionId("s")
    private fun uri(reference: String) = reference.removePrefix("\${").removeSuffix("}")

    @Test fun resolvesOnlyForTheAskingAgentAndSessionFor24Hours() = runTest {
        val time = TestTimeSource()
        val secrets = Secrets(time = time)
        val reference = secrets.session.put(agent, session, "private-value")
        assertTrue(reference.matches(Regex("""\$\{secret://[0-9a-f-]{36}}""")))
        assertEquals("private-value", secrets.scoped(agent, session).resolve(uri(reference), "test"))
        assertEquals("token=private-value", secrets.scoped(agent, session).expand("token=$reference", emptyMap(), "test"))
        assertFailsWith<IllegalStateException> { secrets.resolve(uri(reference), "config") }
        assertFailsWith<IllegalStateException> { secrets.scoped(AgentId("b"), session).resolve(uri(reference), "test") }
        assertFailsWith<IllegalStateException> { secrets.scoped(agent, SessionId("other")).resolve(uri(reference), "test") }
        time += 24.hours - 1.minutes
        assertEquals("private-value", secrets.scoped(agent, session).resolve(uri(reference), "test"))
        time += 1.minutes
        val expired = assertFailsWith<IllegalStateException> { secrets.scoped(agent, session).resolve(uri(reference), "test") }
        assertTrue("private-value" !in expired.message.orEmpty())
        assertEquals("private-value here", secrets.redact("private-value here"))
    }

    @Test fun masksTypedValuesInToolOutputUntilTheSessionIsDeleted() = runTest {
        val secrets = Secrets()
        val reference = secrets.session.put(agent, session, "hunter22")
        val echo = object : CoreTool {
            override val descriptor = toolSpec("echo", "Echo")
            override suspend fun execute(args: JsonObject) = "got hunter22"
            override suspend fun execute(args: JsonObject, callId: String, run: ToolRun) = execute(args)
        }
        assertEquals("got ••••", secrets.redacting(echo).execute(JsonObject(emptyMap())))
        secrets.session.put(agent, session, "xy")
        assertEquals("xy", secrets.redact("xy"))
        secrets.session.clear(session)
        assertFailsWith<IllegalStateException> { secrets.scoped(agent, session).expand(reference, emptyMap(), "test") }
        assertEquals("got hunter22", secrets.redact("got hunter22"))
    }
}
