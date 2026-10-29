package kinuvo.execution.api

import kinuvo.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/** Implementations are native in-process, JVM ACP, or an explicitly selected remote Agent. */
interface ExecutionSession {
    val capabilities: ExecutionCapabilities
    suspend fun close() {}
    suspend fun prompt(text: String)
    suspend fun cancel()
    suspend fun respond(requestId: String, answer: JsonElement): Boolean? = null
    suspend fun answer(requestId: String, answer: JsonElement)
}

/** Execution-owned reducer. Client disconnection never changes execution state or pending requests. */
class ExecutionState(sessionId: String, private val changed: suspend (ExecutionSnapshot) -> Unit = {}, initial: ExecutionSnapshot = ExecutionSnapshot(sessionId)) {
    private val lock = Mutex()
    private var state = initial
    private val resolved = mutableSetOf<String>()

    suspend fun snapshot(): ExecutionSnapshot = lock.withLock { state }

    /** Prompt acceptance has no completion semantics. */
    suspend fun reference(id: String) = lock.withLock { state = state.copy(externalSessionId = id); changed(state) }

    suspend fun begin() = lock.withLock {
        check(state.phase == ExecutionPhase.Idle) { "Execution is not idle" }
        state = state.copy(phase = ExecutionPhase.Running, stopReason = null)
        changed(state)
    }

    suspend fun accepted() = lock.withLock {
        check(state.phase != ExecutionPhase.Lost) { "Execution connection was lost" }
    }

    suspend fun update(phase: ExecutionPhase, stopReason: String? = null) = lock.withLock {
        check(state.phase != ExecutionPhase.Lost) { "Resume must establish a new execution generation" }
        state = state.copy(phase = phase, stopReason = if (phase == ExecutionPhase.Idle) stopReason else null)
        changed(state)
    }

    suspend fun replaceMessages(messages: List<ExecutionMessage>) = lock.withLock { state = state.copy(messages = messages); changed(state) }

    suspend fun message(message: ExecutionMessage) = lock.withLock {
        val messages = state.messages.toMutableList()
        if (message.role == "user" && !message.id.startsWith("local:"))
            messages.removeAll { it.id.startsWith("local:") && it.role == "user" && it.text == message.text }
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0) messages.add(message) else messages[index] = message
        state = state.copy(messages = messages)
        changed(state)
    }

    suspend fun request(request: ExecutionRequest) = lock.withLock {
        check(state.phase != ExecutionPhase.Lost) { "Execution connection was lost" }
        if (request.id !in resolved && state.pending.none { it.id == request.id })
            state = state.copy(pending = state.pending + request)
        changed(state)
    }

    /** Claims a response exactly once, even if several reconnected clients answer. */
    suspend fun resolve(id: String): Boolean = lock.withLock {
        if (state.phase == ExecutionPhase.Lost || state.pending.none { it.id == id } || !resolved.add(id)) false
        else { state = state.copy(pending = state.pending.filterNot { it.id == id }); changed(state); true }
    }

    suspend fun lost() = lock.withLock {
        state = state.copy(phase = ExecutionPhase.Lost, pending = emptyList(), stopReason = null)
        changed(state)
    }
}
