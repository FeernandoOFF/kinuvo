@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
package kinuvo.cli

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.client.v2.*
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PROTOCOL_VERSION_V2
import com.agentclientprotocol.model.v2.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kinuvo.execution.acp.KinuvoAgent
import kinuvo.client.KinuvoClient
import kinuvo.harness.server.Harness
import kinuvo.harness.server.HarnessServer
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.testing.scriptedModel
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class AcpServerTest {
    @Test fun nativeServerReplyReachesSdkClient() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-native-acp").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = HarnessServer(Harness(home = Path(dir.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(dir.path, "none.json"))))), modelFactory = { scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("native ACP reply"), StreamFrame.End()) } }))
        var native: KinuvoClient? = null
        var agentProtocol: Protocol? = null
        var clientProtocol: Protocol? = null
        try {
            withTimeout(20000) {
                val port = server.start("127.0.0.1", 0, schedule = false)
                val connection = KinuvoClient("ws://127.0.0.1:$port/ws", "acp-test", server.auth.sessionToken())
                native = connection; connection.connect()
                val incoming = Channel<String>(Channel.UNLIMITED)
                val outgoing = Channel<String>(Channel.UNLIMITED)
                val ap = Protocol(scope, StdioTransport(scope, Dispatchers.Default, incoming.receiveAsFlow(), outgoing::send))
                val cp = Protocol(scope, StdioTransport(scope, Dispatchers.Default, outgoing.receiveAsFlow(), incoming::send))
                agentProtocol = ap; clientProtocol = cp
                Agent(ap, KinuvoAgent(connection.acpDispatch(), "default"))
                val client = Client(cp, object : ElicitationHandler {
                    override suspend fun createElicitation(request: CreateElicitationRequest): CreateElicitationResponse = error("Unexpected form")
                })
                ap.start(); cp.start()
                client.initialize(ClientInfo(PROTOCOL_VERSION_V2, Implementation("integration", "1")))
                val session = client.newSession(dir.path)
                session.prompt(listOf(ContentBlock.Text("hello")))
                val updates = mutableListOf<SessionUpdate>()
                session.updates.takeWhile { update ->
                    updates.add(update.update)
                    val state = (update.update as? SessionUpdate.StateUpdate)?.state
                    state !is StateUpdate.Idle
                }.collect()
                assertTrue(updates.filterIsInstance<SessionUpdate.AgentMessage>().any { it.message.toString().contains("native ACP reply") })
                assertTrue(updates.any { it is SessionUpdate.UserMessage })
            }
        } finally {
            agentProtocol?.close(); clientProtocol?.close(); scope.cancel(); native?.close(); server.stop(); dir.deleteRecursively()
        }
    }
}
