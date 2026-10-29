package kinuvo.harness.server

import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

class FrameDeliveryTest {
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
