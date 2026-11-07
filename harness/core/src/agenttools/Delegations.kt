package kinuvo.harness.core.agenttools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.tools.Action
import kinuvo.harness.core.tools.Assessment
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.requireString
import kinuvo.harness.core.tools.string
import kinuvo.harness.core.update
import kinuvo.protocol.Risk
import kinuvo.protocol.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

const val DEFAULT_DELEGATION_DEPTH = 2

/** How long a child may wait on an unanswered question or Approval before its parent stops waiting, as for a Routine. */
val CHILD_ANSWER_WAIT = 30.minutes

/** A Session [agentId] was given to run [goal] in, by the parent's tool call [callId]; [hidden] ones keep their transcript from every Agent tool. */
data class DelegateTask(val agentId: String, val goal: String, val context: String? = null, val model: String? = null, val hidden: Boolean = false, val callId: String? = null) {
    /** The child's first message: what it is to do and what it needs to know, never the parent's transcript. */
    val message get() = if (context.isNullOrBlank()) goal else "$goal\n\nContext:\n$context"
}

data class Child(val agentId: String, val sessionId: String)

/** What a child answered; [failed] when its run errored. */
data class ChildResult(val text: String, val failed: Boolean = false)

/** What the Server does for delegations: it owns the Sessions, their runs and the user's Approvals. */
interface AgentDelegation {
    /** Creates the Session [parent] hands [task] to and starts its run; fails when its Agent can't take it, e.g. a hidden task on a model that isn't on-device. */
    suspend fun delegate(parent: CallerScope, task: DelegateTask): Child

    /** Waits for [child]'s run to end and returns what it answered. */
    suspend fun outcome(child: Child): ChildResult

    /** Stops [sessionId]'s run, and the runs it delegated in turn. */
    suspend fun stop(sessionId: String)

    /** Shows [result] to the user in [parent]'s Session, for its tool call [callId] when it is a native run; whether they let it through. */
    suspend fun review(parent: CallerScope, callId: String?, child: Child, result: String): Boolean
}

/** The delegation tools: children running in the Sessions of the Agents they were handed to, and the ones started in the background. */
@OptIn(ExperimentalAtomicApi::class)
internal class Delegations(private val source: AgentToolsSource, private val scope: CoroutineScope, private val answerWait: Duration = CHILD_ANSWER_WAIT) {
    /** The children whose run hasn't ended, by parent Session. */
    private val running = AtomicReference(emptyMap<String, Set<Child>>())

    /** Background children, by parent Session, until `delegate_wait` hands over their result. */
    private val background = AtomicReference(emptyMap<String, Map<String, Deferred<ChildResult>>>())

    fun tools(caller: CallerScope): List<Tool> = listOf(Delegate(caller), DelegateWait(caller), Subagents(caller))

    suspend fun stopChildren(sessionId: String) = running.load()[sessionId].orEmpty().forEach { source.stop(it.sessionId) }

    private suspend fun begin(parent: CallerScope, task: DelegateTask): Child {
        val child = source.delegate(parent, task)
        running.update { it + (parent.sessionId!! to it[parent.sessionId].orEmpty() + child) }
        return child
    }

    private suspend fun finish(parent: CallerScope, callId: String?, child: Child, task: DelegateTask, review: Boolean): ChildResult {
        val result = awaited(child)
        // A child that waits on the user is still running, so stopping the parent stops it.
        if (result !== UNANSWERED) running.update { it + (parent.sessionId!! to it[parent.sessionId].orEmpty() - child) }
        return if (review && task.hidden && !result.failed && !source.review(parent, callId, child, result.text)) ChildResult(WITHHELD) else result
    }

    /** [child]'s outcome, or a failure once it has waited [answerWait] on the user; its Session stays open for the user to answer. */
    private suspend fun awaited(child: Child): ChildResult = coroutineScope {
        val outcome = async { source.outcome(child) }
        val stalled = async {
            var waited = Duration.ZERO
            while (waited < answerWait) {
                delay(POLL)
                waited = if (source.live(child.sessionId).status == SessionStatus.Waiting) waited + POLL else Duration.ZERO
            }
            UNANSWERED
        }
        select<ChildResult> { outcome.onAwait { it }; stalled.onAwait { it } }.also { outcome.cancel(); stalled.cancel() }
    }

    private suspend fun foreground(parent: CallerScope, callId: String?, task: DelegateTask, review: Boolean): Pair<Child, ChildResult> {
        val child = begin(parent, task)
        try {
            return child to finish(parent, callId, child, task, review)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { source.stop(child.sessionId) }
            throw e
        }
    }

    private suspend fun spawn(parent: CallerScope, callId: String?, task: DelegateTask, review: Boolean): Child {
        val child = begin(parent, task)
        val result = scope.async { runCatching { finish(parent, callId, child, task, review) }.getOrElse { if (it is CancellationException) throw it else ChildResult("Failed: ${it.message}", true) } }
        background.update { it + (parent.sessionId!! to it[parent.sessionId].orEmpty() + (child.sessionId to result)) }
        return child
    }

    private inner class Delegate(caller: CallerScope) : ReadTool(source, caller) {
        override val risk = Risk.Medium
        override val descriptor = RawSchemaToolDescriptor(
            "delegate",
            "Hand work to an Agent, which runs it in a new Session of its own: yourself with a fresh context, or another Agent from agents_list. " +
                "It sees only the goal and context you give, never this conversation, so make them self-contained. By default you wait and get its final answer. " +
                "With background you get its Session id at once and fetch the answer with delegate_wait. Several tasks run in parallel (at most $MAX_TASKS). " +
                "hidden keeps the work private: the Agent must run on an on-device model, its transcript is hidden from every Agent tool, and you only get its final answer. " +
                "review (with hidden) first shows the user the answer to allow or withhold. Children can ask the user questions in their own Session; you keep waiting meanwhile.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    taskProperties().forEach { (name, schema) -> put(name, schema) }
                    putJsonObject("background") { put("type", "boolean"); put("description", "Return at once with the Session ids instead of waiting (default false)") }
                    putJsonObject("review") { put("type", "boolean"); put("description", "For hidden tasks: the user sees the answer and decides whether it reaches you (default false)") }
                    putJsonObject("tasks") {
                        put("type", "array")
                        put("description", "Several tasks to run in parallel, instead of the single goal above")
                        putJsonObject("items") {
                            put("type", "object")
                            put("properties", JsonObject(taskProperties()))
                            put("required", buildJsonArray { add(JsonPrimitive("goal")) })
                        }
                    }
                }
                put("required", JsonArray(emptyList()))
            },
        )

        override fun assess(args: JsonObject) = Assessment(listOf(Action(Risk.Medium, listOf(descriptor.name), "Starts an Agent working in a new Session")))

        override suspend fun execute(args: JsonObject) = run(args, null)
        override suspend fun execute(args: JsonObject, callId: String) = run(args, callId)

        private suspend fun run(args: JsonObject, callId: String?): String {
            val a = args.clean()
            val parent = scope.also { requireNotNull(it.sessionId) { "Delegating needs a Session" } }
            val tasks = ((a["tasks"] as? JsonArray)?.map { task(it as? JsonObject ?: throw IllegalArgumentException("Every task is an object")) } ?: listOf(task(a))).map { it.copy(callId = callId) }
            require(tasks.size in 1..MAX_TASKS) { "Give 1 to $MAX_TASKS tasks" }
            val known = source.agents().map { it.id }.toSet()
            tasks.forEach { require(it.agentId in known) { "No Agent '${it.agentId}'; see agents_list" } }
            val review = a.bool("review")
            val single = a["tasks"] == null
            if (a.bool("background")) return background(parent, callId, tasks, review, single)
            if (single) {
                val (child, result) = foreground(parent, callId, tasks.single(), review)
                check(!result.failed) { result.text }
                return "${result.text}\n\n(Session ${child.sessionId} on ${child.agentId})"
            }
            return coroutineScope {
                tasks.mapIndexed { i, task ->
                    async {
                        "Task ${i + 1} (${task.agentId}): " + try {
                            val (child, result) = foreground(parent, callId, task, review)
                            "${if (result.failed) "failed. " else ""}${result.text}\n(Session ${child.sessionId})"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "failed. ${e.message}"
                        }
                    }
                }.awaitAll().joinToString("\n\n")
            }
        }

        private suspend fun background(parent: CallerScope, callId: String?, tasks: List<DelegateTask>, review: Boolean, single: Boolean): String {
            val started = tasks.map { task ->
                try { "- ${spawn(parent, callId, task, review).sessionId} (${task.agentId}): ${task.goal.take(GOAL_EXCERPT)}" }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (single) throw e else "- not started (${task.agentId}): ${e.message}" }
            }
            return "Started in the background; fetch the answers with delegate_wait.\n" + started.joinToString("\n")
        }

        private fun task(o: JsonObject): DelegateTask = o.clean().let {
            DelegateTask(it.string("agent") ?: scope.agentId, it.requireString("goal"), it.string("context"), it.string("model"), it.bool("hidden"))
        }

        private fun taskProperties() = buildMap {
            put("agent", buildJsonObject { put("type", "string"); put("description", "The Agent's id from agents_list; default: yourself") })
            put("goal", buildJsonObject { put("type", "string"); put("description", "What the Agent is to do") })
            put("context", buildJsonObject { put("type", "string"); put("description", "What it needs to know to do it") })
            put("model", buildJsonObject { put("type", "string"); put("description", "A model of the Agent's provider to use instead of its own") })
            put("hidden", buildJsonObject { put("type", "boolean"); put("description", "Keep the work private; needs an on-device model (default false)") })
        }
    }

    private inner class DelegateWait(caller: CallerScope) : ReadTool(source, caller) {
        override val descriptor = ToolDescriptor(
            name = "delegate_wait",
            description = "Wait for the children you started in the background and return the ones that finished. Waits for all of them unless any is set. A child's answer is returned once.",
            optionalParameters = listOf(
                ToolParameterDescriptor("session_ids", "Wait only for these Sessions, separated by commas; default all", ToolParameterType.String),
                ToolParameterDescriptor("any", "true: return as soon as one finishes", ToolParameterType.String),
                ToolParameterDescriptor("timeout_seconds", "How long to wait (default $DEFAULT_WAIT, at most $MAX_WAIT)", ToolParameterType.String),
            ),
        )

        override suspend fun execute(args: JsonObject): String {
            val a = args.clean()
            val parentId = requireNotNull(scope.sessionId) { "Waiting needs a Session" }
            val pending = background.load()[parentId].orEmpty()
            val ids = a.string("session_ids")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ids?.forEach { requireNotNull(pending[it]) { "No background child '$it'" } }
            val selected = pending.filterKeys { ids == null || it in ids }
            if (selected.isEmpty()) return "No children running in the background."
            val timeout = (a.int("timeout_seconds") ?: DEFAULT_WAIT).coerceIn(1, MAX_WAIT)
            withTimeoutOrNull(timeout * 1000L) {
                if (a.bool("any")) select<Unit> { selected.values.forEach { d -> d.onAwait { } } } else selected.values.awaitAll()
            }
            val done = selected.filterValues { it.isCompleted }.keys.associateWith { selected.getValue(it).await() }
            background.update { all -> all + (parentId to all[parentId].orEmpty() - done.keys) }
            return buildString {
                done.forEach { (id, result) -> append("Session $id${if (result.failed) " failed" else ""}:\n${result.text}\n\n") }
                val waiting = selected.keys - done.keys
                if (waiting.isNotEmpty()) append("Still running: ${waiting.joinToString()}")
            }.trimEnd()
        }
    }

    private inner class Subagents(caller: CallerScope) : ReadTool(source, caller) {
        override val descriptor = ToolDescriptor(
            name = "subagents",
            description = "The children you delegated to from this Session: Session id, Agent, goal and state. Hidden ones show only that, never their transcript. action=cancel with session_id stops one.",
            optionalParameters = listOf(
                ToolParameterDescriptor("action", "list (default) or cancel", ToolParameterType.String),
                ToolParameterDescriptor("session_id", "The child to cancel", ToolParameterType.String),
            ),
        )

        override fun assess(args: JsonObject) =
            if (args.clean().string("action") == "cancel") Assessment(listOf(Action(Risk.Medium, listOf(descriptor.name, "cancel"), "Stops a delegated Session's run"))) else super.assess(args)

        override suspend fun execute(args: JsonObject): String {
            val a = args.clean()
            val parentId = requireNotNull(scope.sessionId) { "This needs a Session" }
            val children = source.delegated(scope.agentId).filter { it.parent?.sessionId == parentId }
            when (val action = a.string("action") ?: "list") {
                "list" -> {}
                "cancel" -> {
                    val child = requireNotNull(children.firstOrNull { it.id == a.requireString("session_id") }) { "No child '${a.string("session_id")}'" }
                    source.stop(child.id)
                    return "Stopped ${child.id}."
                }
                else -> throw IllegalArgumentException("action must be list or cancel, got '$action'")
            }
            if (children.isEmpty()) return "No children."
            return children.map { c ->
                val state = source.live(c.id).status.name.lowercase().let { if (it == "idle") "finished" else it }
                "- ${c.id} ${c.agentId} \"${c.title}\" [$state${if (c.hidden) ", hidden" else ""}]"
            }.joinToString("\n")
        }
    }

    private companion object {
        const val MAX_TASKS = 4
        const val DEFAULT_WAIT = 120
        const val MAX_WAIT = 3600
        const val GOAL_EXCERPT = 80
        const val WITHHELD = "The user withheld the result."
        val POLL = 30.seconds
        val UNANSWERED = ChildResult("Waiting on the user: the child's question or Approval went unanswered for 30 minutes, so I stopped waiting. Its Session stays open for the user to answer.", true)
    }
}
