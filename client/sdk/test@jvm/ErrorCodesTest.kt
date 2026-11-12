package kinuvo.client

import kinuvo.protocol.*
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.connection.ConnectResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class ErrorCodesTest {
    private fun client(error: WireError?, connectError: WireError? = null): KinuvoClient {
        val responses = Channel<Frame>(Channel.UNLIMITED)
        val connection = object : FrameConnection {
            override val incoming = responses.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                val reply = when (request.method) {
                    Api.Connect.name -> connectError?.let { Frame.Response(request.id, false, error = it) }
                        ?: Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(Api.Connect.result, ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true)))
                    Api.Presence.name -> Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(Unit.serializer(), Unit))
                    else -> Frame.Response(request.id, false, error = error)
                }
                responses.send(reply)
            }
            override suspend fun close() { responses.close() }
        }
        return KinuvoClient("local:codes", "Device", token = "saved", connection = { connection })
    }

    private fun failure(error: WireError?): KinuvoException = runBlocking {
        val client = client(error)
        try {
            withTimeout(5_000) {
                client.connect()
                assertFailsWith<KinuvoException> { client.call(Api.Agents, Unit) }
            }
        } finally { client.close() }
    }

    @Test fun codesBecomeTypedExceptions() {
        assertIs<KinuvoException.InvalidParams>(failure(WireError(WireErrorCode.InvalidParams, "Bad")))
        assertIs<KinuvoException.NotFound>(failure(WireError(WireErrorCode.NotFound, "No agent 'x'")))
        assertIs<KinuvoException.Conflict>(failure(WireError(WireErrorCode.Conflict, "Connection has live Sessions")))
        assertIs<KinuvoException.Unavailable>(failure(WireError(WireErrorCode.Unavailable, "Offline")))
        val data = JsonPrimitive("main")
        val missing = failure(WireError(WireErrorCode.NotFound, "No agent 'x'", data))
        assertEquals(WireErrorCode.NotFound, missing.code)
        assertEquals("No agent 'x'", missing.message)
        assertEquals(data, missing.data)
    }

    @Test fun managementRefusalsAreWordedByTheClient() {
        val data = buildJsonObject { put("reason", WireErrorReason.Management) }
        val forbidden = failure(WireError(WireErrorCode.Forbidden, "Something the Server worded", data))
        assertIs<KinuvoException.Forbidden>(forbidden)
        assertTrue(forbidden.management)
        assertEquals(ManagementAccessMessage, forbidden.message)
        assertEquals(ManagementAccessMessage, forbidden.userMessage)
        assertEquals(data, forbidden.data)
    }

    @Test fun otherRefusalsShowTheServersMessage() {
        val forbidden = failure(WireError(WireErrorCode.Forbidden, "Agent is not exposed"))
        assertIs<KinuvoException.Forbidden>(forbidden)
        assertFalse(forbidden.management)
        assertEquals("Agent is not exposed", forbidden.message)
        assertEquals("Agent is not exposed", assertIs<KinuvoException.Forbidden>(failure(WireError(WireErrorCode.Forbidden, "Agent is not exposed", buildJsonObject { put("reason", "other") }))).message)
    }

    @Test fun requestReturnsErrorsAsValues() = runBlocking {
        val client = client(WireError(WireErrorCode.NotFound, "No agent 'x'"))
        try {
            withTimeout(5_000) {
                client.connect()
                assertEquals(KinuvoError.NotFound("No agent 'x'"), client.request(Api.Agents, Unit).leftOrNull())
            }
        } finally { client.close() }
        val management = client(WireError(WireErrorCode.Forbidden, "Nope", buildJsonObject { put("reason", WireErrorReason.Management) }))
        try {
            withTimeout(5_000) {
                management.connect()
                val error = assertIs<KinuvoError.Forbidden>(management.request(Api.Agents, Unit).leftOrNull())
                assertTrue(error.management)
                assertEquals(ManagementAccessMessage, error.message)
            }
        } finally { management.close() }
        val closed = client(null)
        closed.close()
        assertIs<KinuvoError.Disconnected>(closed.request(Api.Agents, Unit).leftOrNull())
    }

    @Test fun unknownCodesAreGenericServerErrors() {
        val unknown = failure(WireError("from_the_future", "Newer failure"))
        assertIs<KinuvoException.Server>(unknown)
        assertEquals("from_the_future", unknown.code)
        assertEquals("Newer failure", unknown.message)
        assertEquals(WireErrorCode.Internal, assertIs<KinuvoException.Server>(failure(null)).code)
    }

    @Test fun connectKeepsAuthenticationAndProtocolFailures() = runBlocking {
        val rejected = client(null, WireError(WireErrorCode.Forbidden, "This device was unpaired"))
        try { assertEquals("This device was unpaired", assertFailsWith<AuthException> { withTimeout(5_000) { rejected.connect() } }.message) }
        finally { rejected.close() }
        val mismatch = client(null, WireError(WireErrorCode.ProtocolMismatch, ProtocolVersion.Mismatch))
        try { assertFailsWith<ProtocolMismatchException> { withTimeout(5_000) { mismatch.connect() } } }
        finally { mismatch.close() }
    }
}
