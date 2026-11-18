package kinuvo.client

import io.ktor.client.plugins.websocket.WebSockets
import kinuvo.protocol.FrameConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface TailcatForward {
    val port: Int
    suspend fun close()
    suspend fun invalidate() = close()
}

/** A paired tunnel: [link] is the secret to keep and [open]; [payload] is what the Server sent while pairing. */
class TailcatPairing(val link: String, val payload: String, val name: String) {
    override fun toString() = "TailcatPairing(name=$name)"
}

interface TailcatForwarder {
    val supported: Boolean
    /** Pairs this Device with the Server that showed [invite]. */
    suspend fun pair(invite: String, device: String): TailcatPairing
    suspend fun open(link: String): TailcatForward
}

object UnsupportedTailcatForwarder : TailcatForwarder {
    override val supported = false
    override suspend fun pair(invite: String, device: String): TailcatPairing = error("Tailcat connections are not supported on this platform yet")
    override suspend fun open(link: String): TailcatForward = error("Tailcat connections are not supported on this platform yet")
}

class SharedTailcatForwarder(private val native: TailcatForwarder) : TailcatForwarder {
    override val supported get() = native.supported
    private val lock = Mutex()
    private class Shared(val forward: TailcatForward, var users: Int = 0)
    private val forwards = mutableMapOf<String, Shared>()

    override suspend fun pair(invite: String, device: String) = native.pair(invite, device)

    override suspend fun open(link: String): TailcatForward = lock.withLock {
        val shared = forwards[link] ?: Shared(native.open(link)).also { forwards[link] = it }
        shared.users++
        object : TailcatForward {
            override val port get() = shared.forward.port
            private var released = false
            override suspend fun close() = withContext(NonCancellable) {
                lock.withLock {
                    if (!released) {
                        released = true
                        shared.users--
                        if (shared.users == 0 && forwards[link] === shared) {
                            forwards.remove(link)
                            shared.forward.close()
                        }
                    }
                }
            }
            override suspend fun invalidate() = withContext(NonCancellable) {
                lock.withLock {
                    if (forwards[link] === shared) {
                        forwards.remove(link)
                        shared.forward.close()
                    }
                }
            }
        }
    }
}

/** Each connection attempt owns its forward, including failed handshakes. */
suspend fun tailcatConnection(link: String, forwarder: TailcatForwarder,
    connect: (suspend (String) -> FrameConnection)? = null,
): FrameConnection {
    check(forwarder.supported) { "Tailcat connections are not supported on this platform yet" }
    val forward = withContext(NonCancellable) { forwarder.open(link) }
    val http = try { httpClient { install(WebSockets) { maxFrameSize = MAX_FRAME_SIZE } } }
    catch (e: Throwable) {
        withContext(NonCancellable) { forward.close() }
        throw e
    }
    try {
        currentCoroutineContext().ensureActive()
        val socket = connect?.invoke("ws://127.0.0.1:${forward.port}/ws") ?: http.connection("ws://127.0.0.1:${forward.port}/ws")
        return object : FrameConnection by socket {
            private val lock = Mutex()
            private val closed = MutableStateFlow(false)
            override val incoming = flow {
                try { emitAll(socket.incoming) }
                finally { if (!closed.value && currentCoroutineContext().isActive) withContext(NonCancellable) { forward.invalidate() } }
            }
            override suspend fun close() = withContext(NonCancellable) {
                lock.withLock {
                    if (!closed.value) {
                        closed.value = true
                        try { socket.close() } finally { try { forward.close() } finally { http.close() } }
                    }
                }
            }
        }
    } catch (e: Throwable) {
        withContext(NonCancellable) { try { forward.invalidate() } finally { try { forward.close() } finally { http.close() } } }
        if (e is CancellationException) throw e
        throw IllegalStateException("Could not reach the Server through Tailcat", e)
    }
}
