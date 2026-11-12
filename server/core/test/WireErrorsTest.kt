package kinuvo.server

import kinuvo.protocol.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentRef
import kinuvo.protocol.connection.*
import kinuvo.protocol.device.*
import kinuvo.server.devices.*
import kinuvo.server.rpc.serverRoutes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.builtins.serializer
import kotlin.test.*

class WireErrorsTest {
    private suspend fun answers(management: Boolean, block: suspend (suspend (Method<*, *>, Any?) -> Frame.Response) -> Unit) {
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val responses = Channel<Frame.Response>(Channel.UNLIMITED)
        val auth = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun management(token: String) = management
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "device"
        }
        val runtime = ServerRuntime(RuntimeFixture().runtime(), auth, serverRoutes())
        val connection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) { if (frame is Frame.Response) responses.send(frame) }
            override suspend fun close() { requests.close() }
        }
        coroutineScope {
            val serving = launch { runtime.serve(connection) }
            try {
                withTimeout(10_000) {
                    requests.send(Frame.Request("connect", Api.Connect.name, ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("device", token = "token"))))
                    assertTrue(responses.receive().ok)
                    var id = 0
                    block { method, params ->
                        @Suppress("UNCHECKED_CAST") val m = method as Method<Any?, *>
                        requests.send(Frame.Request("r${++id}", m.name, ProtocolJson.encodeToJsonElement(m.params, params)))
                        responses.receive()
                    }
                }
            } finally { serving.cancelAndJoin(); runtime.close() }
        }
    }

    @Test fun managementRefusalsSayWhy() = runBlocking {
        answers(management = false) { ask ->
            val refused = ask(Api.ServerRules, Unit).error
            assertEquals(WireErrorCode.Forbidden, refused?.code)
            assertEquals(WireErrorReason.Management, refused?.reason)
        }
    }

    @Test fun raisedFeatureErrorsKeepTheirCodeAndMessage() = runBlocking {
        answers(management = true) { ask ->
            val missing = ask(Api.Agent, AgentRef(AgentId("nobody"))).error
            assertEquals(WireError(WireErrorCode.NotFound, "No agent 'nobody'"), missing)
            assertNull(missing?.reason)
            assertEquals(WireError(WireErrorCode.NotFound, "Unknown method nope"), ask(Method("nope", Unit.serializer(), Unit.serializer()), Unit).error)
        }
    }
}
