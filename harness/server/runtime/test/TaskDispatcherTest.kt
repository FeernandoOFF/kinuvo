package kinuvo.harness.server

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.Model
import kinuvo.protocol.SessionEntry
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
    fun cleanup() = runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin(); harness.close(); kinuvo.harness.core.store.deleteTree(home) }
    private val home = Path(SystemTemporaryDirectory, "harness-tasks-${Uuid.random()}")
    private var finish = true
    private var fail = false
    private val model = Model { _, history, tools ->
        assertTrue(tools.any { it.name == kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.tasks"), "tasks").modelAlias() })
        val prompt = history.filterIsInstance<SessionEntry.UserMessage>().first().text
        val id = Regex("""\[Task ([^\]]+)]""").find(prompt)!!.groupValues[1]
        if (fail) flow {
            emit(StreamFrame.TextDelta("Partial reply"))
            error("Model failed")
        } else if (finish && history.none { it is SessionEntry.ToolResult }) flowOf(
            StreamFrame.TextDelta("Working on it."),
            StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.tasks"), "tasks").modelAlias(), """{"action":"update","id":"$id","status":"done"}"""), StreamFrame.End(),
        ) else flowOf(StreamFrame.TextDelta("On it."), StreamFrame.End())
    }
    private val harness = runBlocking { HarnessRuntime(ServerInstallation.assemble(AgentFiles(home)), modelFactory = { model }) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = Runs(harness.log, scope)
    private val dispatcher = TaskDispatcher(harness, runs, scope)

    @Test
    fun agentsWorkOnReadyTasks() = runBlocking {
        val agent = harness.store.agents().single()
        harness.store.saveConfig(agent, agent.config.copy(tools = emptyList()))
        val done = harness.tasks.create("Renew passport", notes = "Original instructions", assignee = "default")
        finish = false
        dispatcher.tick()
        withTimeout(10_000) { while (harness.tasks.get(done.id).status == TaskStatus.InProgress || harness.tasks.get(done.id).sessionIds.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(TaskStatus.Waiting, harness.tasks.get(done.id).status)
        assertEquals("Original instructions\n\n${agent.name}: On it.", harness.tasks.get(done.id).notes)

        harness.tasks.update(done.id, status = TaskStatus.Todo)
        finish = true
        dispatcher.tick()
        withTimeout(10_000) { while (harness.tasks.get(done.id).sessionIds.size < 2 || runs.isActive(harness.tasks.get(done.id).sessionIds.last())) kotlinx.coroutines.delay(20) }
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
        withTimeout(10_000) {
            while (harness.tasks.get(task.id).sessionIds.isEmpty() || runs.isActive(harness.tasks.get(task.id).sessionIds.last())) kotlinx.coroutines.delay(20)
        }
        assertEquals(TaskStatus.Waiting, harness.tasks.get(task.id).status)
        assertEquals("Original instructions", harness.tasks.get(task.id).notes)
    }

    @Test
    fun runsCountWhilePreparingAndReleaseWhenPreparationFails() = runBlocking {
        val agent = harness.store.agents().first()
        val session = harness.newSession(agent)
        val preparing = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = scope.async {
            kotlin.runCatching { runs.start(agent, session.id, "hi") { preparing.await(); error("no runner") } }
        }
        withTimeout(5_000) { while (runs.activity.value.runs != 1) kotlinx.coroutines.delay(10) }
        preparing.complete(Unit)
        assertTrue(started.await().isFailure)
        assertEquals(0, runs.activity.value.runs)
    }
}
