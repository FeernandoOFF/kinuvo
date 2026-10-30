package kinuvo.execution.api

import kinuvo.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

/** Lives with the execution connection, never with an individual Device connection. */
class ExecutionBroker(private val state: ExecutionState) {
    private val lock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val validators = mutableMapOf<String, (JsonElement) -> Unit>()
    private val sourceIds = mutableSetOf<String>()
    private var closed = false

    suspend fun ask(request: ExecutionRequest, validate: (JsonElement) -> Unit = {}): JsonElement {
        val id = Uuid.random().toString()
        val normalized = request.copy(id = id, prompt = request.prompt?.withId(id))
        val answer = lock.withLock {
            check(!closed) { "Execution connection was lost" }
            check(sourceIds.add(request.id)) { "Duplicate request ID" }
            CompletableDeferred<JsonElement>().also {
                pending[id] = it
                validators[id] = validate
                state.request(normalized)
            }
        }
        try { return answer.await() }
        finally { lock.withLock { pending.remove(id); validators.remove(id); sourceIds.remove(request.id) }; state.resolve(id) }
    }

    suspend fun answer(id: String, value: JsonElement): Boolean = lock.withLock {
        val answer = pending[id] ?: return@withLock false
        validators[id]?.invoke(value)
        if (!state.resolve(id)) return@withLock false
        answer.complete(value)
    }

    suspend fun lost() = lock.withLock {
        closed = true
        pending.values.forEach { it.cancel() }
        pending.clear()
        validators.clear()
        state.lost()
    }
}
