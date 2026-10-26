package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.LocalTaskProvider
import kinuvo.harness.core.store.deleteTree
import kinuvo.harness.core.store.saveTasksConfig
import kinuvo.harness.core.tasks.TaskProvider
import kinuvo.harness.core.tasks.TasksConfig
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.PluginDefinition
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
        val local = LocalTaskProvider(files.localTasks)
        var timeoutNextRead = false
        var reads = 0
        var timeouts = 0
        val provider = object : TaskProvider by local {
            override val id = "test"
            override suspend fun lists() = local.lists().also {
                reads++
                if (timeoutNextRead) {
                    timeoutNextRead = false
                    timeouts++
                    withTimeout(1) { awaitCancellation() }
                }
            }
        }
        val plugin = object : HarnessPlugin {
            override val definition = PluginDefinition("test.tasks", "Test tasks")
            override val taskProviders = listOf(provider)
        }
        files.saveTasksConfig(TasksConfig(provider = provider.id, pollMinutes = 1))
        val harness = HarnessRuntime(ServerInstallation.assemble(files, plugins = listOf(plugin)), modelFactory = {
            scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("Result"), StreamFrame.End()) }
        })
        val runs = Runs(harness.log, backgroundScope)
        val dispatcher = TaskDispatcher(harness, runs, backgroundScope)
        try {
            var task = if (timeoutAtStartup) harness.tasks.create("Research", assignee = "default") else null
            timeoutNextRead = timeoutAtStartup
            dispatcher.start()
            runCurrent()
            if (!timeoutAtStartup) {
                task = harness.tasks.create("Research", assignee = if (wakeByAssignment) "" else "default")
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
            if (wakeByAssignment) harness.tasks.update(id, assignee = "default")
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
