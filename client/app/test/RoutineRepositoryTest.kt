package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineRepositoryTest {
    @Test fun failedLoadCanRetryAndTimeoutStopsLoading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = Channel<Frame>(Channel.UNLIMITED)
        var failure = true
        var refusal = WireError(WireErrorCode.Forbidden, "This action requires management access", buildJsonObject { put("reason", WireErrorReason.Management) })
        var drop = false
        val transport = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                if (request.method == Api.Connect.name) {
                    frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))))
                } else if (request.method == Api.Presence.name) {
                    frames.send(Frame.Response(request.id, true, buildJsonObject {}))
                } else if (!drop) {
                    frames.send(Frame.Response(request.id, !failure, JsonArray(emptyList()), if (failure) refusal else null))
                }
            }
            override suspend fun close() { frames.close() }
        }
        val client = KinuvoClient("local:test", "test", connection = { transport }, dispatcher = StandardTestDispatcher(testScheduler))
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        try {
            client.connect()
            server.open(client, "local:test")
            server.routines.load(AgentId("devops"))
            runCurrent()
            assertEquals("This action requires management access", server.routines.errors.value[AgentId("devops")])
            assertNull(server.routines.state.value[AgentId("devops")])
            refusal = WireError(WireErrorCode.Forbidden, "Agent is not exposed")
            server.routines.load(AgentId("devops"))
            runCurrent()
            assertEquals("Agent is not exposed", server.routines.errors.value[AgentId("devops")])
            failure = false
            server.routines.load(AgentId("devops"))
            runCurrent()
            assertNull(server.routines.errors.value[AgentId("devops")])
            assertEquals(emptyList(), server.routines.state.value[AgentId("devops")])
            drop = true
            server.routines.load(AgentId("devops"))
            advanceTimeBy(15_001)
            runCurrent()
            assertTrue(server.routines.errors.value.getValue(AgentId("devops")).contains("too long"))
            server.close()
            assertTrue(server.routines.errors.value.isEmpty())
        } finally {
            server.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
