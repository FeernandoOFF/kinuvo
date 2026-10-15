package kinuvo.protocol

import kotlinx.coroutines.flow.Flow

/** One protocol connection, over a WebSocket or inside the process. */
interface FrameConnection {
    val credential: String? get() = null
    val incoming: Flow<Frame>
    suspend fun send(frame: Frame)
    suspend fun close()
}

