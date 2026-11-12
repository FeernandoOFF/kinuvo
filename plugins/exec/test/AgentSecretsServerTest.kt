package kinuvo.plugins.exec

import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.RespondParams
import kinuvo.protocol.session.CatchUpParams
import kinuvo.protocol.session.HistoryParams
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionParams
import kinuvo.protocol.session.SessionStatus
import kinuvo.server.Secrets
import kinuvo.server.store.AgentFiles
import kinuvo.testing.harness.TemporaryDirectories
import kinuvo.testing.harness.awaitCondition
import kinuvo.testing.harness.awaitValue
import kinuvo.testing.server.FakeSecretProvider
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** `request_secret` against a real Server: the user answers privately and only `shell` sees the value, as an environment variable. */
class AgentSecretsServerTest {
    private val temporary = TemporaryDirectories()
    private val home = temporary.create("kinuvo-secrets")
    private val workspace = temporary.create("workspace").toFile().canonicalFile
    private val time = TestTimeSource()
    private val pass = FakeSecretProvider(mapOf(listOf("Work", "GitHub", "token") to PICKED))
    private val secrets = Secrets(listOf(pass), time = time)
    private val work = AgentId("work")
    private var reused = ""

    @AfterTest fun cleanUp() = temporary.close()

    private fun shell(command: String, reference: String) =
        """{"command":${JsonPrimitive(command)},"secrets":{"TOKEN":${JsonPrimitive(reference)}}}"""

    private suspend fun server() = TestServer.start(
        home = Path(home.toString()),
        plugins = listOf(ExecPlugin(AgentFiles(Path(home.toString())), secrets)),
        secrets = secrets,
        script = {
            val ask = """{"label":"GitHub token","reason":"Publish the release"}"""
            on("typed") {
                toolCall("request_secret", ask)
                toolCall("shell") { shell("""ps -o args= -p $$ > argv.txt; printf %s "${'$'}TOKEN" > token.txt; echo "token ${'$'}TOKEN"""", it[0]) }
                reply("Published")
            }
            on("picked") {
                toolCall("request_secret", ask)
                toolCall("shell") { shell("""echo "token ${'$'}TOKEN"""", it[0]) }
                reply("Published")
            }
            on("declined") { toolCall("request_secret", ask); reply("Skipped") }
            on("reuse") { toolCall("shell") { shell("echo reused", reused) }; reply("Tried") }
        },
        scenario = {
            withoutDefaultAgent()
            agent("work") { it.copy(exec = "host", workspace = listOf(workspace.path), approvals = "full") }
            agent("other") { it.copy(exec = "host", workspace = listOf(workspace.path), approvals = "full") }
        },
    )

    /** Sends [text] in [session] and answers its secret card with [answer]; returns the turn's tool results and every update [client] saw. */
    private suspend fun TestServer.turn(client: KinuvoClient, session: SessionInfo, text: String, answer: InteractionAnswer.Secret?): Pair<List<SessionEntry.ToolResult>, List<String>> = withContext(Dispatchers.Default) {
        suspend fun results() = client.call(Api.History, HistoryParams(session.agentId, session.id, 100)).map { it.entry }.filterIsInstance<SessionEntry.ToolResult>()
        val before = results().size
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val watching = launch(start = CoroutineStart.UNDISPATCHED) { client.events(Api.Update).collect { seen += it.toString() } }
        client.call(Api.CatchUp, CatchUpParams(session.agentId, session.id))
        client.call(Api.Send, SendParams(session.agentId, session.id, text))
        if (answer != null) {
            val card = awaitValue("the secret card") { harness.log.pending(session.id).filterIsInstance<PendingInteraction.Secret>().firstOrNull() }
            assertEquals("GitHub token", card.label)
            assertEquals("Publish the release", card.reason)
            assertTrue(client.call(Api.Respond, RespondParams(session.id, card.id, answer)))
        }
        awaitCondition("the run to end", 10.seconds) { results().size > before && client.call(Api.Session, SessionParams(session.agentId, session.id)).status == SessionStatus.Idle }
        watching.cancel()
        results().drop(before) to seen.toList()
    }

    private fun stored() = home.toFile().walkTopDown().filter { it.isFile }.map { it.readText(Charsets.ISO_8859_1) }.toList()

    @Test fun aTypedValueReachesShellOnlyAsAnEnvironmentVariable() = runTest {
        server().use { server ->
            val client = server.client().also { it.connect() }
            val session = server.startSession(work)
            val (results, updates) = server.turn(client, session, "typed", InteractionAnswer.Secret(value = TYPED))
            val reference = results.single { it.name == "request_secret" }.output
            assertTrue(reference.startsWith("\${secret://"), reference)
            assertEquals(TYPED, File(workspace, "token.txt").readText())
            val argv = File(workspace, "argv.txt").readText()
            assertTrue("\$TOKEN" in argv && TYPED !in argv, argv)
            val output = results.single { it.name == "shell" }.output
            assertTrue("token ••••" in output && TYPED !in output, output)
            assertTrue(updates.isNotEmpty() && updates.none { TYPED in it })
            assertTrue(stored().none { TYPED in it })
            assertFalse(TYPED in server.prompts.toString())
        }
    }

    @Test fun aPickedFieldIsReadFromThePasswordManagerWhenShellRuns() = runTest {
        server().use { server ->
            val client = server.client().also { it.connect() }
            val session = server.startSession(work)
            val (results, updates) = server.turn(client, session, "picked", InteractionAnswer.Secret(reference = "\${pass://Work/GitHub/token}"))
            assertEquals("\${pass://Work/GitHub/token}", results.single { it.name == "request_secret" }.output)
            assertEquals(listOf("pass://Work/GitHub/token" to "Kinuvo: shell"), pass.reads)
            val output = results.single { it.name == "shell" }.output
            assertTrue("token ••••" in output && PICKED !in output, output)
            assertTrue(updates.none { PICKED in it } && stored().none { PICKED in it })
        }
    }

    @Test fun aDeclineReachesTheAgentWithoutAValue() = runTest {
        server().use { server ->
            val client = server.client().also { it.connect() }
            val session = server.startSession(work)
            val (results, _) = server.turn(client, session, "declined", InteractionAnswer.Secret())
            assertTrue("Don't ask for this credential in chat" in results.single().output)
        }
    }

    @Test fun aTypedValueStaysWithItsAgentAndSessionFor24Hours() = runTest {
        server().use { server ->
            val client = server.client().also { it.connect() }
            val session = server.startSession(work)
            reused = server.turn(client, session, "typed", InteractionAnswer.Secret(value = TYPED)).first.single { it.name == "request_secret" }.output

            val sameSession = server.turn(client, session, "reuse", null).first.single()
            assertEquals("[exit code 0]\nreused", sameSession.output)
            val otherSession = server.turn(client, server.startSession(work), "reuse", null).first.single()
            assertTrue(otherSession.isError && "another Session" in otherSession.output, otherSession.output)
            val otherAgent = server.turn(client, server.startSession(AgentId("other")), "reuse", null).first.single()
            assertTrue(otherAgent.isError && "another Session" in otherAgent.output, otherAgent.output)

            time += 24.hours
            val expired = server.turn(client, session, "reuse", null).first.single()
            assertTrue(expired.isError && "expired" in expired.output, expired.output)
        }
    }

    private companion object {
        const val TYPED = "typed-value-1234"
        const val PICKED = "picked-value-5678"
    }
}
