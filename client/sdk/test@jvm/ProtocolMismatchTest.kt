package kinuvo.client

import kinuvo.protocol.*
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class ProtocolMismatchTest {
    private fun ok(version: Int = ProtocolVersion.Current) = Frame.Response("", true,
        ProtocolJson.encodeToJsonElement(ConnectResult.serializer(), ConnectResult("t", "Server", capabilities = ServerCapabilities("s"), management = true, protocolVersion = version)))

    /** Answers each connect with the next of [replies]; [drop] ends the latest connection. */
    private class Scripted(replies: List<Frame.Response>) {
        val remaining = replies.toMutableList()
        val sent = mutableListOf<ConnectParams>()
        var current: Channel<Frame>? = null
        fun connection() = object : FrameConnection {
            val responses = Channel<Frame>(Channel.UNLIMITED).also { current = it }
            override val incoming = responses.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val request = frame as Frame.Request
                if (request.method == Api.Connect.name) {
                    sent += ProtocolJson.decodeFromJsonElement(ConnectParams.serializer(), request.params)
                    responses.send(remaining.removeFirst().copy(id = request.id))
                } else responses.send(Frame.Response(request.id, true, JsonObject(emptyMap())))
            }
            override suspend fun close() { responses.close() }
        }
        fun drop() { current?.close() }
    }

    @Test fun incompatibleRepliesDoNotDecodeFeaturesOrRejectSavedCredentials() = runBlocking {
        for (reply in listOf(
            Frame.Response("", false, error = WireError(WireErrorCode.ProtocolMismatch, ProtocolVersion.Mismatch)),
            Frame.Response("", true, ProtocolJson.parseToJsonElement("""{"protocolVersion":3}""")),
            Frame.Response("", true, ProtocolJson.parseToJsonElement("""{"capabilities":{"features":["FutureFeature"]}}""")),
        )) {
            var savedToken = "saved"
            val script = Scripted(List(ProtocolVersion.Current - ProtocolVersion.Minimum + 1) { reply })
            val client = KinuvoClient("local:version", "Device", token = savedToken, onToken = { savedToken = it }, connection = { script.connection() })
            try {
                withTimeout(5_000) { assertFailsWith<ProtocolMismatchException> { client.connect() } }
                assertEquals("saved", savedToken)
                val state = assertIs<ConnectionState.Disconnected>(client.state.value)
                assertFalse(state.rejected)
                assertTrue(state.incompatible)
            } finally { client.close() }
        }
    }

    @Test fun theMismatchSaysWhichSideToUpdate() {
        assertEquals("This Server is too old for this app. Update the Server.",
            ProtocolMismatchException(ProtocolRange(ProtocolVersion.Minimum - 2, ProtocolVersion.Minimum - 1)).message)
        assertEquals("This app is too old for this Server. Update the app.",
            ProtocolMismatchException(ProtocolRange(ProtocolVersion.Current + 1, ProtocolVersion.Current + 2)).message)
    }

    @Test fun otherConnectFailuresAreRetriedWithoutDroppingCredentials() = runBlocking {
        var savedToken = "saved"
        val script = Scripted(listOf(Frame.Response("", false, error = WireError(WireErrorCode.InvalidParams, "Unknown field")), ok()))
        val client = KinuvoClient("local:version", "Device", token = savedToken, onToken = { savedToken = it }, connection = { script.connection() })
        try {
            val result = withTimeout(5_000) { client.connect(retry = true) }
            assertEquals(ProtocolVersion.Current, result.protocolVersion)
            assertEquals("t", savedToken)
            assertEquals(ProtocolVersion.Minimum, script.sent.first().minProtocolVersion)
        } finally { client.close() }
    }

    @Test fun aMismatchAfterConnectingIsReportedAndRetried() = runBlocking {
        val script = Scripted(listOf(ok()) + List(ProtocolVersion.Current - ProtocolVersion.Minimum + 1) {
            Frame.Response("", false, error = WireError(WireErrorCode.ProtocolMismatch, ProtocolVersion.Mismatch))
        })
        val client = KinuvoClient("local:version", "Device", token = "saved", connection = { script.connection() })
        try {
            withTimeout(5_000) { client.connect() }
            script.drop()
            val state = withTimeout(5_000) { client.state.first { it is ConnectionState.Disconnected && it.incompatible } }
            assertFalse((state as ConnectionState.Disconnected).rejected)
        } finally { client.close() }
    }
}
