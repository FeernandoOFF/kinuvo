package kinuvo.client

import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

class ProtocolMismatchTest {
    @Test fun incompatibleRepliesDoNotDecodeFeaturesOrRejectSavedCredentials() = runBlocking {
        for (reply in listOf(
            Frame.Response("", false, error = ProtocolVersion.Mismatch),
            Frame.Response("", true, ProtocolJson.parseToJsonElement("""{"protocolVersion":3}""")),
            Frame.Response("", true, ProtocolJson.parseToJsonElement("""{"capabilities":{"features":["FutureFeature"]}}""")),
        )) {
            val responses = Channel<Frame>(Channel.UNLIMITED)
            var savedToken = "saved"
            val connection = object : FrameConnection {
                override val incoming = responses.receiveAsFlow()
                override suspend fun send(frame: Frame) {
                    val request = frame as Frame.Request
                    responses.send(reply.copy(id = request.id))
                }
                override suspend fun close() { responses.close() }
            }
            val client = KinuvoClient("local:version", "Device", token = savedToken,
                onToken = { savedToken = it }, connection = { connection })
            try {
                withTimeout(5_000) { assertFailsWith<ProtocolMismatchException> { client.connect() } }
                assertEquals("saved", savedToken)
                val state = assertIs<ConnectionState.Disconnected>(client.state.value)
                assertFalse(state.rejected)
                assertEquals(ProtocolVersion.Mismatch, state.reason)
            } finally { client.close() }
        }
    }
}
