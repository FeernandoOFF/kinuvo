package kinuvo.server.embedded

import kinuvo.server.plugin.TailcatServing
import kinuvo.server.plugin.TailcatTunnel
import kotlinx.coroutines.*
import kotlinx.coroutines.IO
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

class EmbeddedSharing(
    private val stateDirectory: Path,
    private val name: String,
    private val native: () -> TailcatServing?,
    private val saved: (Boolean) -> Boolean,
    private val save: (Boolean, Boolean) -> Unit,
    private val lan: suspend (Boolean) -> Unit,
    private val start: suspend () -> Int,
    private val stop: suspend () -> Unit,
    private val publish: (TailcatTunnel?, Boolean) -> Unit,
) {
    enum class State { Unavailable, Off, Starting, On, Error }
    @Volatile var state = State.Off
        private set
    private val lock = Mutex()
    private var tunnel: TailcatTunnel? = null
    val enabled get() = tunnel != null

    suspend fun restore() = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            lan(saved(false))
            update()
            if (native() == null) state = State.Unavailable
            else if (saved(true)) {
                try { attempt { enable() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
        }
    }

    suspend fun shareLan(enabled: Boolean) = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            save(false, enabled)
            update()
            lan(enabled)
        }
    }

    suspend fun share(enabled: Boolean) = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            attempt {
                if (enabled) enable() else disable()
                save(true, enabled)
                update()
            }
        }
    }

    suspend fun reset() = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            attempt {
                val restart = saved(true)
                disable()
                requireNotNull(native()).reset(stateDirectory)
                if (restart) enable()
            }
        }
    }

    suspend fun close() = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            try { disable() } finally { lan(false); publish(null, false) }
        }
    }

    private suspend fun enable() {
        if (tunnel != null) return
        val api = native() ?: run { state = State.Unavailable; error("Tailcat library unavailable") }
        state = State.Starting
        try {
            val port = start()
            tunnel = api.serve(stateDirectory, port, name)
            state = State.On
            update()
        } catch (e: Throwable) {
            disable()
            throw e
        }
    }

    private suspend fun disable() {
        val active = tunnel
        tunnel = null
        try { update(); active?.close() } finally { stop(); state = State.Off; update() }
    }

    private fun update() = publish(tunnel, saved(false) || saved(true) || enabled)

    private suspend fun attempt(action: suspend () -> Unit) {
        try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (state != State.Unavailable) state = State.Error; throw e }
    }
}
