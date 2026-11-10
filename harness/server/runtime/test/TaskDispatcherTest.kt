package kinuvo.harness.server

import kotlin.time.Duration.Companion.seconds
import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.execution.api.ExecutionBroker
import kinuvo.harness.execution.api.ExecutionFactory
import kinuvo.harness.execution.api.ExecutionInput
import kinuvo.harness.execution.api.ExecutionSession
import kinuvo.harness.execution.api.ExecutionState
import kinuvo.protocol.AgentRegistration
import kinuvo.protocol.HarnessConnection
import kinuvo.protocol.TaskStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.uuid.Uuid
import kinuvo.harness.core.store.AgentFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskDispatcherTest {
    @kotlin.test.AfterTest
    fun cleanup() = runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin(); harness.close(); kinuvo.server.plugin.deleteTree(home) }
    private val home = Path(SystemTemporaryDirectory, "harness-tasks-${Uuid.random()}")
    private var finish = true
    private var fail = false
    private val model = scriptedModel { prompt, tools ->
        val history = prompt.messages
        assertTrue(tools.any { it.name == "tasks" })
        val prompt = history.filterIsInstance<Message.User>().first().textContent()
        val id = Regex("""\[Task ([^\]]+)]""").find(prompt)!!.groupValues[1]
        if (fail) flow {
            emit(StreamFrame.TextDelta("Partial reply"))
            error("Model failed")
        } else if (finish && history.toolResults.isEmpty()) flowOf(
            StreamFrame.TextDelta("Working on it."),
            StreamFrame.ToolCallComplete("c1", "tasks", """{"action":"update","id":"$id","status":"done"}"""), StreamFrame.End(),
        ) else flowOf(StreamFrame.TextDelta("On it."), StreamFrame.End())
    }
    private val harness = runBlocking { HarnessRuntime(ServerInstallation.assemble(AgentFiles(home)), modelFactory = { model }) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = Runs(harness.log, scope, harness.native)
    private val dispatcher = TaskDispatcher(harness, runs, scope)

    @Test
    fun agentsWorkOnReadyTasks() = runBlocking {
        val agent = harness.store.agents().single()
        harness.store.saveConfig(agent, agent.config.copy(tools = emptyList()))
        val done = harness.tasks.create("Renew passport", notes = "Original instructions", assignee = "default")
        finish = false
        dispatcher.tick()
        awaitCondition("the first run to finish") { harness.tasks.get(done.id).let { it.status != TaskStatus.InProgress && it.sessionIds.isNotEmpty() } }
        assertEquals(TaskStatus.Waiting, harness.tasks.get(done.id).status)
        assertEquals("Original instructions\n\n${agent.name}: On it.", harness.tasks.get(done.id).notes)

        harness.tasks.update(done.id, status = TaskStatus.Todo)
        finish = true
        dispatcher.tick()
        awaitCondition("the second run to finish") { harness.tasks.get(done.id).sessionIds.let { it.size >= 2 && !runs.isActive(it.last()) } }
        val task = harness.tasks.all(includeDone = true).single()
        assertEquals(TaskStatus.Done, task.status)
        assertEquals("Original instructions\n\n${agent.name}: On it.\n\n${agent.name}: On it.", task.notes)
        assertTrue(harness.tasks.all().isEmpty())

        harness.tasks.create("Unassigned", assignee = "")
        dispatcher.tick()
        assertTrue(harness.tasks.all().single().sessionIds.isEmpty())
    }

    @Test
    fun failedRunsKeepPartialRepliesInTheSession() = runBlocking {
        val agent = harness.store.agents().single()
        harness.store.saveConfig(agent, agent.config.copy(tools = emptyList()))
        val task = harness.tasks.create("Research", notes = "Original instructions", assignee = "default")
        fail = true
        dispatcher.tick()
        awaitCondition("the run to finish") { harness.tasks.get(task.id).sessionIds.let { it.isNotEmpty() && !runs.isActive(it.last()) } }
        assertEquals(TaskStatus.Waiting, harness.tasks.get(task.id).status)
        assertEquals("Original instructions", harness.tasks.get(task.id).notes)
    }

    @Test
    fun runsCountWhilePreparingAndReleaseWhenPreparationFails() = runBlocking {
        val agent = harness.store.agents().first()
        val session = harness.newSession(agent)
        val preparing = kotlinx.coroutines.CompletableDeferred<Unit>()
        val failing = object : ExecutionFactory {
            override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession { preparing.await(); error("no session") }
        }
        val failingRuns = Runs(harness.log, scope, failing)
        val started = scope.async {
            kotlin.runCatching { failingRuns.start(agent, session.id, ExecutionInput("hi")) }
        }
        awaitCondition("the run to be counted", 5.seconds) { failingRuns.activity.value.runs == 1 }
        preparing.complete(Unit)
        assertTrue(started.await().isFailure)
        assertEquals(0, failingRuns.activity.value.runs)
    }
}
