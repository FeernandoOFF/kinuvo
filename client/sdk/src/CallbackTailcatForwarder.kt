package kinuvo.client

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Adapts a callback-style native tunnel (a Swift object on iOS): [start] reports a loopback port, or 0 on failure; [stop] reports when the port is released. */
class CallbackTailcatForwarder(
    private val start: (address: String, completion: (port: Int) -> Unit) -> Unit,
    private val stop: (port: Int, completion: () -> Unit) -> Unit,
) : TailcatForwarder {
    override val supported = true

    override suspend fun open(address: String): TailcatForward {
        val port = suspendCancellableCoroutine { continuation ->
            start(address) { port ->
                if (continuation.isActive) continuation.resume(port) else if (port > 0) stop(port) {}
            }
        }
        check(port > 0) { "Could not reach the Server through Tailcat" }
        return object : TailcatForward {
            override val port = port
            private val lock = Mutex()
            private var closed = false
            override suspend fun close() = withContext(NonCancellable) {
                lock.withLock {
                    if (!closed) {
                        closed = true
                        suspendCancellableCoroutine { done -> stop(port) { done.resume(Unit) } }
                    }
                }
            }
        }
    }
}
