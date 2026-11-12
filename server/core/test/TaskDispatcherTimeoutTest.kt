package kinuvo.server

import kinuvo.protocol.agent.AgentId
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.LocalTaskApp
import kinuvo.server.plugin.deleteTree
import kinuvo.server.store.saveTasksConfig
import kinuvo.server.plugin.TaskApp
import kinuvo.server.plugin.TaskProvider
import kinuvo.server.tasks.TasksConfig
import kinuvo.testing.harness.scriptedModel
import kinuvo.protocol.plugin.PluginDefinition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDispatcherTimeoutTest {
    @Test fun startupTimeoutDoesNotStopDispatching() = recovery(timeoutAtStartup = true)

    @Test fun pollingContinuesAfterProviderTimeout() = recovery()

    @Test fun assignmentWakesDispatcherAfterProviderTimeout() = recovery(wakeByAssignment = true)

    @Test fun cancellationDuringProviderReadStopsDispatching() = recovery(cancelDuringRead = true)

    private fun recovery(timeoutAtStartup: Boolean = false, wakeByAssignment: Boolean = false, cancelDuringRead: Boolean = false) = runTest {
        val home = Path(SystemTemporaryDirectory, "dispatcher-timeout-${Uuid.random()}")
        val files = AgentFiles(home)
        val local = LocalTaskApp(files.localTasks)
        var timeoutNextRead = false
        var reads = 0
        var timeouts = 0
        val provider = TaskProvider.of("test", "Test", object : TaskApp by local {
            override suspend fun lists() = local.lists().also {
                reads++
                if (timeoutNextRead) {
                    timeoutNextRead = false
                    timeouts++
                    withTimeout(1) { awaitCancellation() }
                }
            }
        })
        val plugin = object : HarnessPlugin {
            override val definition = PluginDefinition("test.tasks", "Test tasks")
            override val taskProviders = listOf(provider)
        }
        files.saveTasksConfig(TasksConfig(provider = provider.id, pollMinutes = 1))
        val harness = testRuntime(ServerInstallation.assemble(files, plugins = listOf(plugin)), modelFactory = {
            scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("Result"), StreamFrame.End()) }
        })
        val runs = Runs(harness.log, backgroundScope, harness.native)
        val dispatcher = TaskDispatcher(harness, runs, backgroundScope)
        try {
            var task = if (timeoutAtStartup) harness.tasks.create("Research", assignee = AgentId("default")) else null
            timeoutNextRead = timeoutAtStartup
            dispatcher.start()
            runCurrent()
            if (!timeoutAtStartup) {
                task = harness.tasks.create("Research", assignee = AgentId(if (wakeByAssignment) "" else "default"))
                timeoutNextRead = true
                runCurrent()
            }
            if (cancelDuringRead) {
                assertEquals(1, timeouts)
                backgroundScope.coroutineContext[Job]!!.cancelAndJoin()
                val readsAfterCancellation = reads
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(readsAfterCancellation, reads)
                assertTrue(harness.tasks.get(requireNotNull(task).id).sessionIds.isEmpty())
                return@runTest
            }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, timeouts)
            val id = requireNotNull(task).id
            if (!timeoutAtStartup) assertTrue(harness.tasks.get(id).sessionIds.isEmpty())
            if (wakeByAssignment) harness.tasks.update(id, assignee = AgentId("default"))
            else advanceTimeBy(60_000)
            runCurrent()
            assertTrue(harness.tasks.get(id).sessionIds.isNotEmpty())

            backgroundScope.coroutineContext[Job]!!.cancelAndJoin()
            val readsAfterCancellation = reads
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(readsAfterCancellation, reads)
        } finally {
            backgroundScope.coroutineContext[Job]!!.cancelAndJoin()
            harness.close()
            deleteTree(home)
        }
    }
}
