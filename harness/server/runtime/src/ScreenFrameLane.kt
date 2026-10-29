package kinuvo.harness.server

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kinuvo.protocol.ScreenFrame
import kotlinx.coroutines.channels.Channel

/** Replaceable images never enter the durable outbox. */
internal class ScreenFrameLane {
    private val lock = Lock()
    private val latest = linkedMapOf<String, ScreenFrame>()
    val ready = Channel<Unit>(Channel.CONFLATED)
    fun offer(frame: ScreenFrame) = lock.withLock {
        if (frame.sample.image.length <= 1024 * 1024 && (frame.subscription in latest || latest.size < 4)) {
            latest[frame.subscription] = frame
            ready.trySend(Unit)
        }
    }
    fun poll(): ScreenFrame? = lock.withLock {
        val key = latest.keys.firstOrNull() ?: return@withLock null
        latest.remove(key).also { if (latest.isNotEmpty()) ready.trySend(Unit) }
    }
    fun remove(subscription: String) = lock.withLock { latest.remove(subscription); Unit }
    fun close() = lock.withLock { latest.clear(); ready.close() }
}

internal class ScreenBudget {
    private val lock = Lock()
    private val counts = mutableMapOf<String, Int>()
    fun acquire(device: String) = lock.withLock {
        val count = counts[device] ?: 0
        check(count < 4) { "Too many Screen subscriptions for this Device" }
        counts[device] = count + 1
    }
    fun release(device: String) = lock.withLock {
        val count = counts[device] ?: return@withLock
        if (count <= 1) counts.remove(device) else counts[device] = count - 1
    }
}
