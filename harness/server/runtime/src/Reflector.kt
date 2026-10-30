package kinuvo.harness.server

import co.touchlab.stately.collections.ConcurrentMutableMap
import kinuvo.harness.core.Agent
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ReflectionResult
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Learns from conversations: once a Session has been quiet for [idle] after a run, the Agent reflects on
 * what was said since its last reflection. How far each Session got is kept in `reflections.json`.
 */
class Reflector(
    private val harness: HarnessRuntime,
    private val scope: CoroutineScope,
    private val busy: (sessionId: String) -> Boolean,
    private val idle: Duration = 2.minutes,
) {
    private val pending = ConcurrentMutableMap<String, Job>()
    private val lock = Mutex()

    /** A run in [sessionId] finished; reflect once the Session has been quiet for a while. A later run restarts the wait. */
    fun ran(agent: Agent, sessionId: String) {
        if (!harness.reflects(agent)) return
        val job = scope.launch {
            delay(idle)
            // Once started, a reflection finishes even if another run ends meanwhile, so its changes and progress match.
            withContext(NonCancellable) { reflect(agent, sessionId) }
        }
        pending.put(sessionId, job)?.cancel()
        job.invokeOnCompletion { pending.removeExact(sessionId, job) }
    }

    /** Reflects on what [sessionId] said since last time; returns how many changes were saved immediately. */
    suspend fun reflect(agent: Agent, sessionId: String): Int = lock.withLock {
        // A turn in progress is reflected on after it ends.
        if (busy(sessionId) || !harness.reflects(agent)) return 0
        val done = progress()
        val entries = harness.store.entries(agent.id, sessionId)
        val new = entries.drop(done[sessionId] ?: 0)
        if (new.none { it is SessionEntry.UserMessage }) return 0
        val id = Uuid.random().toString()
        harness.log.publish(agent.id, sessionId, AgentEvent.ReflectionStarted(id))
        var result = ReflectionResult()
        try {
            result = harness.reflect(agent, sessionId, new)
        } catch (e: CancellationException) {
            result = result.copy(error = "Reflection interrupted")
            throw e
        } catch (e: Exception) {
            result = result.copy(error = e.message ?: e.toString())
        } finally {
            withContext(NonCancellable) {
                val safe = result.copy(error = result.error?.let { harness.secrets.redact(it) })
                harness.log.append(agent.id, sessionId, SessionEntry.Reflection(id, Clock.System.now().toEpochMilliseconds(), safe))
                if (result.error == null || result.total > 0 || result.proposals.isNotEmpty()) {
                    harness.reflectionProgress.save(done + (sessionId to entries.size))
                }
            }
        }
        result.total
    }

    private fun progress(): Map<String, Int> =
        harness.reflectionProgress.get()
}
