package kinuvo.server

import kinuvo.protocol.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.device.*
import kinuvo.protocol.session.*
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.devices.*
import kinuvo.server.plugin.RunContext
import kinuvo.server.rpc.serverRoutes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DeviceRegistrationOrderTest {
    @Test fun aDeviceReadWaitingForTheDeviceIsSentAfterTheConnectAnswer() = runBlocking(Dispatchers.Default) {
        val device = ToolDevice("phone", "iPhone", setOf(DeviceToolKind.Contacts))
        val fixture = RuntimeFixture()
        val harness = fixture.runtime()
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val sent = Channel<Frame>(Channel.UNLIMITED)
        val readSent = CompletableDeferred<Unit>()
        val peers = object : PeerRegistry {
            private var held = false
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun toolDevices() = listOf(device)
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = device.id
            // Holds the Connect handler long enough for an early registration to send its read first.
            override fun management(token: String): Boolean {
                if (!held) { held = true; runBlocking { withTimeoutOrNull(500) { readSent.await() } } }
                return false
            }
        }
        val connection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                if (frame is Frame.Request && frame.method == Api.DeviceRead.name) readSent.complete(Unit)
                sent.send(frame)
            }
            override suspend fun close() { requests.close(); sent.close() }
        }
        val server = ServerRuntime(harness, peers, serverRoutes())
        val agent = fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf(ToolNames.DEVICE_TOOLS)))
        val session = harness.newSession(agent)
        val parked = CompletableDeferred<Unit>()
        harness.log.onEvent { _, _, event -> if (event.questions != null) parked.complete(Unit) }
        harness.log.publish(agent.id, session.id, AgentEvent.RunStarted(RunId("run")))
        harness.devices.paired = { listOf(device) }
        val tool = harness.devices.prepare(RunContext(agent, session.id), activate = true).tools.single { it.descriptor.name == "contacts_lookup" }
        val serving = launch { server.serve(connection) }
        try {
            withTimeout(10_000) {
                val read = async { tool.execute(buildJsonObject { put("query", "Ada") }, "call") }
                parked.await()
                requests.send(Frame.Request("connect", Api.Connect.name,
                    ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("phone", token = "token", deviceTools = device.tools))))
                val first = sent.receive()
                assertTrue(first is Frame.Response && first.id == "connect", "The Connect answer must reach the client first, got $first")
                val request = sent.receiveAsFlow().filterIsInstance<Frame.Request>().first { it.method == Api.DeviceRead.name }
                requests.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(DeviceToolResult.serializer(), DeviceToolResult(DeviceToolStatus.Ready))))
                assertEquals(DeviceToolStatus.Ready, ProtocolJson.decodeFromString(DeviceToolResult.serializer(), read.await()).status)
            }
        } finally { serving.cancelAndJoin(); server.close() }
    }
}
