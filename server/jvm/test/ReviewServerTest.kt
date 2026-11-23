package kinuvo.server.jvm

import java.io.File
import kinuvo.harness.AgentDoc
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentRef
import kinuvo.protocol.agent.CreateAgentParams
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.device.PairingParams
import kinuvo.protocol.model.CustomEndpointParams
import kinuvo.server.DeviceAuth
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.tool.ToolNames
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class ReviewServerTest {
    @Test fun reviewDevicesCanReadRoutinesButCannotAdministerTheServer() = runBlocking<Unit> {
        val temporary = TemporaryDirectories()
        val home = Path(temporary.create("review-access").toString())
        ReviewServer.prepare(home)
        val runtime = JvmAgentRuntime(home, review = true)
        ReviewServer.seed(runtime)
        val code = "review-access-code-".repeat(3)
        val auth = DeviceAuth(Path(home, "devices.json"))
        auth.reviewPairing(code, Clock.System.now() + 30.days, management = true)
        val host = JvmServerHost(runtime, auth, review = true)
        val port = host.start("127.0.0.1", 0, schedule = false)
        val client = KinuvoClient("ws://127.0.0.1:$port/ws", "reviewer", pairingCode = code)
        try {
            client.connect()
            assertTrue(client.lastConnect?.management == true)
            val agent = client.call(Api.Agents, Unit).single()
            assertEquals(3, client.call(Api.Routines, AgentRef(agent.id)).size)
            assertFails { client.call(Api.CreateAgent, CreateAgentParams(AgentId("forbidden"))) }
            assertFails { client.call(Api.Pairing, PairingParams()) }
            assertFails { client.call(Api.SaveEndpoint, CustomEndpointParams(baseUrl = "https://example.com")) }
        } finally { client.close(); host.stop(); temporary.close() }
    }

    @Test fun isolatedSeedAndToolsWorkWithoutProviderCredentials() = runBlocking {
        val temporary = TemporaryDirectories()
        val home = Path(temporary.create("review-server").toString())
        ReviewServer.prepare(home)
        val runtime = JvmAgentRuntime(home, review = true)
        try {
            ReviewServer.seed(runtime)
            ReviewServer.seed(runtime)
            val agent = runtime.store.agents().single()
            assertEquals(3, runtime.store.sessions(agent.id).size)
            assertEquals(5, runtime.tasks.all(includeDone = true).size)
            assertEquals(2, runtime.routines.list(agent).count { !it.heartbeat })
            assertTrue(runtime.routines.list(agent).none { it.enabled })
            assertTrue(runtime.docs.read(agent.id, AgentDoc.UserNotes).contains("Maya Chen"))
            assertTrue(runtime.knowledge.search("Lisbon").isNotEmpty())
            assertFalse(runtime.liveVoiceEnabled)
            assertFalse(runtime.reflectionEnabled)
            assertNull(runtime.notifier())
            assertNotNull(runtime.webSearch())
            val expanded = agent.copy(config = agent.config.copy(tools = ToolNames.DEFAULT))
            val run = runtime.composer.compose(expanded, requireModel = false)
            assertTrue(run.tools.tools.all { it.name in ReviewServer.tools })
            assertTrue(run.tools.tools.any { it.name == ToolNames.MEMORY })
            assertTrue(run.tools.tools.any { it.name == ToolNames.TASKS })
            val capabilities = runtime.composer.capabilities(expanded, null, null, ServerCapabilities(runtime.serverId))
            assertTrue(capabilities.tools.all { it.id in ReviewServer.tools })
        } finally { runtime.close(); temporary.close() }
    }

    @Test fun refusesExistingNonReviewData() {
        val temporary = TemporaryDirectories()
        try {
            val directory = temporary.create("personal-data").toFile()
            File(directory, "config.yaml").writeText("timezone: Europe/Dublin\n")
            assertFailsWith<IllegalArgumentException> { ReviewServer.prepare(Path(directory.path)) }
            assertEquals("timezone: Europe/Dublin\n", File(directory, "config.yaml").readText())
        } finally { temporary.close() }
    }
}
