package kinuvo.harness.core

import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Capture production never waits for a viewer or a draw acknowledgement. */
interface ScreenSource {
    val ref: ScreenRef
    val capabilities: ScreenCapabilities
    fun frames(): Flow<ScreenSample>
}

/** Shared exclusion primitive. Reserve before draining so queued Agent work cannot slip past Takeover. */
class ScreenGate(initial: ScreenControl = ScreenControl()) {
    private val control = MutableStateFlow(initial)
    val state: StateFlow<ScreenControl> = control.asStateFlow()
    private val execution = Mutex()

    suspend fun <T> agent(expected: Long = state.value.generation, block: suspend () -> T): T = execution.withLock {
        val current = state.value
        check(current.generation == expected && current.holder == null && !current.draining && !current.suspended) { "Screen control changed; observe again" }
        block()
    }

    suspend fun acquire(holder: String, expected: Long, timeoutMillis: Long = 10_000): ScreenControl {
        require(holder.isNotBlank())
        val old = state.value
        check(old.generation == expected && !old.draining) { "Screen control changed" }
        check(old.holder == null || old.suspended) { "Screen already controlled" }
        val reserved = ScreenControl(expected + 1, holder, draining = true)
        check(control.compareAndSet(old, reserved)) { "Screen control changed" }
        try {
            withTimeout(timeoutMillis) { execution.withLock {} }
            currentCoroutineContext().ensureActive()
            check(control.compareAndSet(reserved, reserved.copy(draining = false))) { "Control interrupted" }
        } catch (e: Throwable) {
            control.compareAndSet(reserved, old.copy(generation = reserved.generation))
            throw e
        }
        return state.value
    }

    suspend fun release(holder: String, expected: Long): ScreenControl = execution.withLock {
        val old = state.value
        check(old.generation == expected && old.holder == holder && !old.draining) { "Screen control changed" }
        val next = ScreenControl(expected + 1)
        check(control.compareAndSet(old, next)) { "Screen control changed" }
        next
    }

    fun disconnect(holder: String) {
        control.update { if (it.holder == holder) it.copy(generation = it.generation + 1, suspended = true, draining = false) else it }
    }

    suspend fun <T> input(holder: String, generation: Long, block: suspend () -> T): T = execution.withLock {
        val current = state.value
        check(current.holder == holder && current.generation == generation && !current.suspended && !current.draining) { "No active Takeover" }
        block()
    }
}
