package kinuvo.client

import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

class RequestLimitTest {
    @Test fun keepsWithinTheServersRequestLimit() = runBlocking {
        val responses = Channel<Frame>(Channel.UNLIMITED)
        val held = mutableListOf<Frame.Request>()
        var open = 0
        var most = 0
        val connected = ProtocolJson.encodeToJsonElement(ConnectResult.serializer(), ConnectResult("t", "Test", capabilities = ServerCapabilities("s"), management = false))
        val connection = object : FrameConnection {
            override val incoming = responses.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                when (request.method) {
                    Api.Connect.name -> responses.send(Frame.Response(request.id, true, connected))
                    Api.Presence.name -> responses.send(Frame.Response(request.id, true, ProtocolJson.parseToJsonElement("{}")))
                    else -> synchronized(held) { held += request; open++; most = maxOf(most, open) }
                }
            }
            override suspend fun close() { responses.close() }
        }
        val client = KinuvoClient("local:limit", "Device", token = "t", connection = { connection })
        try {
            withTimeout(5_000) {
                client.connect()
                val calls = List(20) { async { client.call(Api.Agents, Unit) } }
                while (calls.any { !it.isCompleted }) {
                    delay(10)
                    val answer = synchronized(held) { held.toList().also { held.clear(); open -= it.size } }
                    answer.forEach { responses.send(Frame.Response(it.id, true, ProtocolJson.parseToJsonElement("[]"))) }
                }
                calls.awaitAll()
            }
            assertTrue(most < MAX_CONCURRENT_REQUESTS, "had $most requests open")
        } finally { client.close() }
    }
}
