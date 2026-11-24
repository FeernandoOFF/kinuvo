package kinuvo.client

import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

class DeviceReadWhileConnectingTest {
    /** The Server registers a Device before it answers Connect, so a waiting read can reach the Device first. */
    @Test fun aReadThatArrivesBeforeTheConnectAnswerIsAnsweredOnceConnected() = runBlocking {
        val responses = Channel<Frame>(Channel.UNLIMITED)
        val answers = Channel<Frame.Response>(Channel.UNLIMITED)
        val connected = ProtocolJson.encodeToJsonElement(ConnectResult.serializer(), ConnectResult("t", "Test", capabilities = ServerCapabilities("s"), management = false))
        val read = Frame.Request("read", Api.DeviceRead.name, ProtocolJson.encodeToJsonElement(Api.DeviceRead.params, DeviceToolRequest(DeviceToolKind.Contacts, query = "Ada")))
        val connection = object : FrameConnection {
            override val incoming = responses.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                when {
                    frame is Frame.Response -> answers.send(frame)
                    frame is Frame.Request && frame.method == Api.Connect.name -> {
                        responses.send(read)
                        delay(300)
                        responses.send(Frame.Response(frame.id, true, connected))
                    }
                    frame is Frame.Request -> responses.send(Frame.Response(frame.id, true, ProtocolJson.parseToJsonElement("{}")))
                }
            }
            override suspend fun close() { responses.close() }
        }
        val client = KinuvoClient("local:read", "Device", token = "t", deviceTools = { setOf(DeviceToolKind.Contacts) },
            deviceRead = { DeviceToolResult(DeviceToolStatus.Ready) }, connection = { connection })
        try {
            withTimeout(10_000) {
                client.connect()
                val answer = answers.receive()
                assertEquals("read", answer.id)
                assertEquals(DeviceToolStatus.Ready, ProtocolJson.decodeFromJsonElement(Api.DeviceRead.result, answer.payload).status)
            }
        } finally { client.close() }
    }
}
