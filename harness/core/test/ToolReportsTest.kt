package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.tools.*
import kinuvo.harness.execution.api.ToolReports
import kinuvo.harness.execution.api.ToolTurn
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class ToolReportsTest {
    @Test fun regularWatchAndBackgroundShareTheCall() = runTest {
        val done = CompletableDeferred<String>()
        val reports = mutableListOf<SessionEntry.ToolReport>()
        val events = mutableListOf<AgentEvent>()
        val tool = object : Tool {
            override val descriptor = ToolDescriptor("worker", "Work")
            override suspend fun execute(args: JsonObject) = "regular"
            override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
                run.progress("first")
                run.progress("second")
                return run.background("started") { done.await() }
            }
        }
        val executor = ToolService(listOf(tool), reports = ToolReports(this) { reports += it })
        assertEquals("regular", tool.execute(JsonObject(emptyMap())))
        assertEquals("started", executor.invoke(SessionEntry.ToolCall("1", "worker", "{}"), ToolTurn(emit = { events += it })).output)
        runCurrent()
        assertTrue(reports.isEmpty())
        assertEquals(listOf("first", "second", "started"), events.filterIsInstance<AgentEvent.ToolCallResult>().map { it.content })
        done.complete("finished")
        runCurrent()
        assertEquals(listOf(SessionEntry.ToolReport("worker", "1", "finished")), reports)
    }

    @Test fun failureReportsOnce() = runTest {
        val reports = mutableListOf<SessionEntry.ToolReport>()
        ToolReports(this) { reports += it }.start("worker", "call") { error("broken") }
        runCurrent()
        assertEquals("Failed: broken", reports.single().content)
    }
}
