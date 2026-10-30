package kinuvo.harness.server

import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

class FrameDeliveryTest {
    @Test fun validScreenAcksDoNotConsumeTheRequestAllowance() = runBlocking {
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val responses = Channel<Frame.Response>(Channel.UNLIMITED)
        val frames = Channel<ScreenFrame>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        val auth = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun toolDevices() = listOf(ToolDevice("source", "Computer", setOf(DeviceToolKind.Computer)))
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "viewer"
        }
        val harness = RuntimeFixture().runtime()
        val runtime = ServerRuntime(harness, auth, security = ConnectionSecurity(messagesPerMinute = 2, screenAcksPerMinute = 3))
        harness.computers.connect("source") { ComputerResult(DeviceToolStatus.Ready,
            screen = ComputerScreen("capture", 10, 10, screenshot = "image")) }
        val connection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                when (frame) {
                    is Frame.Response -> responses.send(frame)
                    is Frame.Event -> if (frame.event == Api.ScreenFrame.name)
                        frames.send(ProtocolJson.decodeFromJsonElement(ScreenFrame.serializer(), frame.payload))
                    else -> Unit
                }
            }
            override suspend fun close() { requests.close(); closed.complete(Unit) }
        }
        val serving = launch { runtime.serve(connection) }
        try {
            withTimeout(10_000) {
                requests.send(Frame.Request("connect", Api.Connect.name,
                    ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("viewer", token = "token"))))
                val connected = responses.receive()
                assertTrue(connected.ok)
                assertEquals("viewer", ProtocolJson.decodeFromJsonElement(ConnectResult.serializer(), connected.payload).deviceId)
                requests.send(Frame.Request("watch", Api.ScreenWatch.name,
                    ProtocolJson.encodeToJsonElement(ScreenWatch.serializer(), ScreenWatch(ScreenRef("computer", "source")))))
                assertTrue(responses.receive().ok)
                repeat(3) { index ->
                    val frame = frames.receive()
                    requests.send(Frame.Request("ack$index", Api.ScreenAck.name,
                        ProtocolJson.encodeToJsonElement(ScreenFrameAck.serializer(), ScreenFrameAck(frame.subscription, frame.epoch, frame.sequence))))
                    assertTrue(responses.receive().ok)
                    assertFalse(closed.isCompleted)
                }
                val frame = frames.receive()
                requests.send(Frame.Request("excess", Api.ScreenAck.name,
                    ProtocolJson.encodeToJsonElement(ScreenFrameAck.serializer(), ScreenFrameAck(frame.subscription, frame.epoch, frame.sequence))))
                closed.await()
            }
        } finally { serving.cancelAndJoin(); runtime.close() }
    }

    @Test fun incompatibleProtocolsAreRejectedBeforeAuthentication() = runBlocking {
        for (params in listOf("""{"device":"legacy"}""", """{"device":"previous","protocolVersion":${ProtocolVersion.Current - 1}}""", """{"device":"future","protocolVersion":${ProtocolVersion.Current + 1}}""")) {
            val requests = Channel<Frame>(Channel.UNLIMITED)
            val response = CompletableDeferred<Frame.Response>()
            var authenticated = false
            val auth = object : PeerRegistry {
                override fun authenticate(device: String, token: String?, pairingCode: String?): String? { authenticated = true; return null }
                override fun toolDevices() = emptyList<ToolDevice>()
                override fun setTools(token: String, tools: Set<DeviceToolKind>) = "device"
            }
            val runtime = ServerRuntime(RuntimeFixture().runtime(), auth)
            val connection = object : FrameConnection {
                override val incoming = requests.receiveAsFlow()
                override suspend fun send(frame: Frame) { response.complete(frame as Frame.Response) }
                override suspend fun close() { requests.close() }
            }
            val serving = launch { runtime.serve(connection) }
            try {
                requests.send(Frame.Request("connect", Api.Connect.name, ProtocolJson.parseToJsonElement(params)))
                val rejected = withTimeout(5_000) { response.await() }
                assertFalse(rejected.ok)
                assertEquals(ProtocolVersion.Mismatch, rejected.error)
                assertFalse(authenticated)
            } finally { serving.cancelAndJoin(); runtime.close() }
        }
    }

    @Test fun failedWriterClosesConnectionEvenWhenIncomingRemainsOpen() = runBlocking {
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        val auth = object : PeerRegistry {
            override fun authenticate(device: String, token: String?, pairingCode: String?) = "token"
            override fun toolDevices() = emptyList<ToolDevice>()
            override fun setTools(token: String, tools: Set<DeviceToolKind>) = "device"
        }
        val runtime = ServerRuntime(RuntimeFixture().runtime(), auth)
        val connection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) { error("Socket write failed") }
            override suspend fun close() { requests.close(); closed.complete(Unit) }
        }
        val serving = launch { runtime.serve(connection) }
        try {
            requests.send(Frame.Request("connect", Api.Connect.name,
                ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("device", token = "token"))))
            withTimeout(5_000) { closed.await(); serving.join() }
            assertTrue(closed.isCompleted)
        } finally { serving.cancelAndJoin(); runtime.close() }
    }
}
