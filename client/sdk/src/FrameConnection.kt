package kinuvo.client

import kinuvo.protocol.FrameConnection
import kinuvo.protocol.Frame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kinuvo.protocol.ProtocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal suspend fun HttpClient.connection(url: String): FrameConnection {
    val socket = webSocketSession(url)
    return object : FrameConnection {
        override val incoming = flow {
            for (frame in socket.incoming) {
                if (frame is WsFrame.Text) {
                    val decoded = runCatching { ProtocolJson.decodeFromString(Frame.serializer(), frame.readText()) }.getOrNull()
                    if (decoded != null) emit(decoded)
                }
            }
        }
        override suspend fun send(frame: Frame) = socket.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), frame)))
        override suspend fun close() { socket.close() }
    }
}
