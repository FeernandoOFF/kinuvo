package kinuvo.harness.server

import io.github.oshai.kotlinlogging.KotlinLogging
import co.touchlab.stately.collections.ConcurrentMutableSet
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.TaskInfo
import kinuvo.protocol.taskPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val log = KotlinLogging.logger("kinuvo.tasks")

/**
 * Starts Agents on their Tasks: every few minutes, and whenever a Task changes, each Task that's ready (to do, assigned,
 * due soon or undated) gets a run in a new Session. A Task runs once at a time; a run that ends with it still in progress,
 * or never starts, leaves it waiting for the user. On start, Tasks left in progress by a previous process wait too.
 */
class TaskDispatcher(private val harness: HarnessRuntime, private val runs: Runs, private val scope: CoroutineScope) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val ticking = Mutex()
    /** Tasks with a run starting or going. */
    private val active = ConcurrentMutableSet<String>()
    private var loop: Job? = null

    fun start() {
        harness.tasks.onChange { wake.trySend(Unit) }
        loop = scope.launch {
            log.info { "Dispatcher started" }
            guarded("reconcile") { harness.tasks.reconcile { it.id in active } }
            while (true) {
                tick()
                val minutes = runCatching { harness.tasksSetup().settings.pollMinutes }.getOrDefault(DEFAULT_POLL).coerceAtLeast(1)
                withTimeoutOrNull(minutes * 60_000L) { wake.receive() }
            }
        }
    }

    /** Starts a run for every ready Task that has none. */
    suspend fun tick() = ticking.withLock {
        val ready = guarded("read the task provider") { harness.tasks.ready() } ?: return@withLock
        ready.filter { it.id !in active }.forEach { start(it) }
    }

    private suspend fun start(task: TaskInfo) {
        val agent = harness.store.agents().firstOrNull { it.id == task.assignee } ?: return
        if (!active.add(task.id)) return
        var claimed = false
        var started = false
        try {
            claimed = harness.tasks.claim(task.id)
            if (!claimed) return
            val session = harness.newSession(agent)
            harness.tasks.started(task.id, session.id)
            log.info { "Task ${task.id}: starting Agent ${agent.id}, Session ${session.id}" }
            var reply: String? = null
            var completed = false
            runs.start(agent, session.id, prompt(task), onEvent = { event ->
                when (event) {
                    is AgentEvent.TextMessageEnd -> reply = event.text
                    is AgentEvent.ToolCallStart -> reply = null
                    is AgentEvent.RunFinished -> completed = true
                    else -> Unit
                }
            }, onDone = { stopped ->
                try {
                    if (!stopped && completed) reply?.takeIf { it.isNotBlank() }?.let {
                        guarded("save task ${task.id} reply") { harness.tasks.appendReply(task.id, agent.name, it) }
                    }
                } finally {
                    done(task.id)
                }
            }) { harness.runner(agent, session.id, taskId = task.id) }
            started = true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val failure = harness.secrets.redact(e.stackTraceToString())
            log.warn { "Task ${task.id}: couldn't start:\n$failure" }
        } finally {
            if (!started) withContext(NonCancellable) { if (claimed) done(task.id) else active.remove(task.id) }
        }
    }

    private suspend fun done(id: String) {
        guarded("finish task $id") { harness.tasks.finished(id) }
        active.remove(id)
    }

    private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        val failure = harness.secrets.redact(e.stackTraceToString())
        log.warn { "Couldn't $what; dispatcher will keep checking:\n$failure" }
        null
    }

    private fun prompt(t: TaskInfo) = buildString {
        append(taskPrompt(t.id, t.title) + "\n")
        t.due?.let { append("Due: $it\n") }
        if (t.notes.isNotBlank()) append("\nNotes:\n${t.notes.trim()}\n")
        append(
            "\nWork on this task from your user's task list. Set it done with the tasks tool when " +
                "it's finished, or set it waiting when the user has to act. Put your result or what you need in your final reply; " +
                "it will be appended to the Task's notes."
        )
    }

    private companion object {
        const val DEFAULT_POLL = 5
    }
}
