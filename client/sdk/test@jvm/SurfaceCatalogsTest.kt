package kinuvo.client

import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.surface.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.*

class SurfaceCatalogsTest {
    @Test fun catalogsAreAuthenticatedBeforeRequestsOnEveryConnection() = runTest {
        val requests = Channel<Frame.Request>(Channel.UNLIMITED)
        val sockets = Channel<Channel<Frame>>(Channel.UNLIMITED)
        val catalogs = listOf(UiCatalog("test", description = "x".repeat(30_000)))
        val client = KinuvoClient("local:test", "Device", token = "token", uiCatalogs = catalogs,
            dispatcher = StandardTestDispatcher(testScheduler), connection = {
                val responses = Channel<Frame>(Channel.UNLIMITED)
                sockets.send(responses)
                object : FrameConnection {
                    override val incoming = responses.receiveAsFlow()
                    override suspend fun send(frame: Frame) { requests.send(frame as Frame.Request) }
                    override suspend fun close() { responses.close() }
                }
            })
        try {
            val connecting = async { client.connect() }
            repeat(2) { attempt ->
                val responses = sockets.receive()
                val connect = requests.receive()
                assertEquals(Api.Connect.name, connect.method)
                val encoded = ProtocolJson.encodeToString(Frame.serializer(), connect)
                assertTrue(encoded.encodeToByteArray().size < 4096)
                assertFalse("catalogs" in encoded)
                responses.send(Frame.Response(connect.id, true, ProtocolJson.encodeToJsonElement(Api.Connect.result,
                    ConnectResult("token", "Test", capabilities = ServerCapabilities("s"), management = false))))
                val registration = requests.receive()
                assertEquals(Api.SurfaceCatalogs.name, registration.method)
                assertEquals(catalogs, ProtocolJson.decodeFromJsonElement(Api.SurfaceCatalogs.params, registration.params).catalogs)
                assertIs<ConnectionState.Connecting>(client.state.value)
                val call = async { client.call(Api.Agents, Unit) }
                yield()
                assertTrue(requests.tryReceive().isFailure)
                responses.send(Frame.Response(registration.id, true, ProtocolJson.parseToJsonElement("{}")))
                repeat(2) {
                    val request = requests.receive()
                    assertTrue(request.method in setOf(Api.Presence.name, Api.Agents.name))
                    responses.send(Frame.Response(request.id, true, ProtocolJson.parseToJsonElement(if (request.method == Api.Agents.name) "[]" else "{}")))
                }
                call.await()
                if (attempt == 0) { connecting.await(); responses.close() }
            }
        } finally { client.close() }
    }
}
