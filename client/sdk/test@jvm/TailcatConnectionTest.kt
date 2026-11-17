package kinuvo.client

import kinuvo.protocol.*
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class TailcatConnectionTest {
    @Test fun droppedConnectionRecreatesForwardAndUsesToken() = runBlocking {
        var opened = 0
        var closed = 0
        val forwarder = object : TailcatForwarder {
            override val supported = true
            override suspend fun pair(invite: String, device: String): TailcatPairing = error("unused")
            override suspend fun open(link: String): TailcatForward {
                opened++
                return object : TailcatForward {
                    override val port = 1234
                    override suspend fun close() { closed++ }
                }
            }
        }
        val requests = Channel<ConnectParams>(Channel.UNLIMITED)
        val sockets = Channel<Channel<Frame>>(Channel.UNLIMITED)
        val connect: suspend (String) -> FrameConnection = { url ->
            assertEquals("ws://127.0.0.1:1234/ws", url)
            val replies = Channel<Frame>(Channel.UNLIMITED)
            sockets.send(replies)
            object : FrameConnection {
                override val incoming = replies.receiveAsFlow()
                override suspend fun send(frame: Frame) {
                    val request = frame as Frame.Request
                    val payload = if (request.method == Api.Connect.name) {
                        requests.send(ProtocolJson.decodeFromJsonElement(ConnectParams.serializer(), request.params))
                        ProtocolJson.encodeToJsonElement(ConnectResult.serializer(), ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = false, protocolVersion = ProtocolVersion.Current))
                    } else JsonObject(emptyMap())
                    replies.send(Frame.Response(request.id, true, payload))
                }
                override suspend fun close() { replies.close() }
            }
        }
        val shared = SharedTailcatForwarder(forwarder)
        val client = KinuvoClient("tailcat:opaque", "Device", pairingCode = "123456", connection = { tailcatConnection("secret", shared, connect) })
        try {
            withTimeout(5_000) {
                client.connect()
                assertEquals("123456", requests.receive().pairingCode)
                sockets.receive().close()
                assertEquals("token", requests.receive().token)
                assertEquals(2, opened)
                assertEquals(1, closed)
            }
        } finally { client.close() }
        withTimeout(5_000) { while (closed < 2) delay(10) }
    }

    @Test fun parallelClientsShareUntilTheLastClosesAndDropsInvalidate() = runBlocking {
        var opened = 0
        var closed = 0
        val pool = SharedTailcatForwarder(object : TailcatForwarder {
            override val supported = true
            override suspend fun pair(invite: String, device: String): TailcatPairing = error("unused")
            override suspend fun open(link: String): TailcatForward {
                opened++
                return object : TailcatForward {
                    override val port = opened
                    override suspend fun close() { closed++ }
                }
            }
        })
        val first = pool.open("secret")
        val second = pool.open("secret")
        assertEquals(1, opened)
        first.close()
        assertEquals(0, closed)
        second.invalidate()
        assertEquals(1, closed)
        val next = pool.open("secret")
        assertEquals(2, opened)
        second.close()
        assertEquals(1, closed)
        next.close()
        assertEquals(2, closed)
    }

    @Test fun failedSocketClosesForward() = runBlocking {
        var closed = false
        val forwarder = object : TailcatForwarder {
            override val supported = true
            override suspend fun pair(invite: String, device: String): TailcatPairing = error("unused")
            override suspend fun open(link: String) = object : TailcatForward {
                override val port = 1
                override suspend fun close() { closed = true }
            }
        }
        val error = assertFailsWith<IllegalStateException> { tailcatConnection("secret", forwarder) { error("failed") } }
        assertEquals("Could not reach the Server through Tailcat", error.message)
        assertEquals("failed", error.cause?.message)
        assertTrue(closed)
    }
}
