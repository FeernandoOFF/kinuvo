package kinuvo.harness.server.execution

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One worker and one authoritative pending value per key, including while delivery is blocked. */
internal class LatestNotifications<K, V>(scope: CoroutineScope, private val deliver: suspend (K, V) -> Unit) {
    private val lock = Lock()
    private val pending = mutableMapOf<K, V>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val job = scope.launch {
        for (signal in wake) {
            delay(32)
            val batch = lock.withLock { pending.toMap().also { pending.clear() } }
            for ((key, value) in batch) deliver(key, value)
        }
    }
    fun offer(key: K, value: V) {
        lock.withLock { pending[key] = value }
        wake.trySend(Unit)
    }
    fun close() { job.cancel(); wake.close(); lock.withLock { pending.clear() } }
}
