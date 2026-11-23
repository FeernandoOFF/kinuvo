package kinuvo.server

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlin.time.TimeSource

internal class RequestRateLimit(private val clock: () -> Long = { started.elapsedNow().inWholeMilliseconds }) {
    private val lock = Lock()
    private data class Window(val started: Long, var count: Int = 0)
    private val windows = mutableMapOf<String, Window>()

    fun accept(key: String, limit: Int): Boolean = lock.withLock {
        val time = clock()
        windows.entries.removeAll { time - it.value.started >= 60_000 }
        if (key !in windows && windows.size >= 4096) return false
        ++windows.getOrPut(key) { Window(time) }.count <= limit
    }

    private companion object { val started = TimeSource.Monotonic.markNow() }
}
