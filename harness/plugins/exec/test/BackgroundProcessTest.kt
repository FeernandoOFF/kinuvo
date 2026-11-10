package kinuvo.harness.exec

import kotlinx.coroutines.withTimeout
import kinuvo.protocol.SessionEntry
import kinuvo.harness.core.tools.ToolService
import kinuvo.harness.execution.api.ToolTurn
import kinuvo.harness.execution.api.ToolReports
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kinuvo.protocol.AgentEvent

class BackgroundProcessTest {
    @Test fun watchTimeoutKeepsOutput() = runBlocking {
        val executor = ToolService(listOf(ShellTool(backend)))
        try {
            val result = executor.invoke(SessionEntry.ToolCall("watch", "shell", """{"command":"echo partial; sleep 30","watch":true,"timeout":1}"""))
            assertTrue("[timed out after 1s]" in result.output && "partial" in result.output, result.output)
        } finally { backend.close() }
    }

    @Test fun killedBackgroundReportsOnce() = runBlocking {
        val reports = Channel<SessionEntry.ToolReport>(Channel.UNLIMITED)
        val executor = ToolService(execTools(backend), reports = ToolReports(this) { reports.send(it) })
        try {
            executor.invoke(SessionEntry.ToolCall("start", "shell", """{"command":"echo partial; sleep 30","background":true}"""))
            delay(200)
            executor.invoke(SessionEntry.ToolCall("kill", "process_kill", """{"id":"bg1"}"""))
            val report = withTimeout(10000) { reports.receive() }
            assertTrue("killed" in report.content, report.content)
            assertTrue(report.callId == "start")
            delay(1200)
            assertTrue(reports.tryReceive().isFailure)
        } finally { backend.close() }
    }

    @Test fun shellReportsCompletionThroughTheGenericRun() = runBlocking {
        val reports = Channel<SessionEntry.ToolReport>(1)
        val reporter = ToolReports(this) { reports.send(it) }
        val executor = ToolService(listOf(ShellTool(backend)), reports = reporter)
        try {
            val result = executor.invoke(SessionEntry.ToolCall("call", "shell", """{"command":"echo completed; exit 7","background":true}"""))
            assertTrue(result.output.startsWith("Started"))
            val report = withTimeout(10000) { reports.receive() }
            assertTrue("exit 7" in report.content && "completed" in report.content, report.content)
        } finally { backend.close() }
    }

    private val root = Files.createTempDirectory("bg").toFile()
    private val backend = HostBackend(listOf(root))
    private val tools = execTools(backend).associateBy { it.descriptor.name }
    private suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = tools.getValue(tool).execute(buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    })

    @Test fun watchShowsOutputAndReturnsTheFinalResult() = runBlocking {
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val executor = ToolService(listOf(ShellTool(backend)))
        try {
            val call = async { executor.invoke(SessionEntry.ToolCall("watch", "shell", """{"command":"echo watching; while [ ! -e release ]; do sleep 0.05; done; echo done","watch":true}"""), ToolTurn(emit = { events.trySend(it) })) }
            withTimeout(30000) {
                do {
                    val shown = events.receive().let { it is AgentEvent.ToolCallResult && "watching" in it.content }
                } while (!shown)
            }
            File(root, "release").createNewFile()
            val result = withTimeout(30000) { call.await() }
            assertTrue("exit 0" in result.output && "done" in result.output, result.output)
        } finally { backend.close() }
    }

    @Test
    fun startCheckAndKill() = runBlocking {
        val started = call("shell", "command" to "sleep 30", "background" to true)
        assertTrue("bg1" in started, started)
        assertTrue(call("process_output", "id" to "bg1").startsWith("[bg1 running]"))
        call("process_kill", "id" to "bg1")
        assertTrue(call("process_output", "id" to "bg1").startsWith("[bg1 stopped"))
    }

    @Test
    fun finishedProcessReportsOutputAndExit() = runBlocking {
        call("shell", "command" to "echo hi; sleep 0.2; echo done", "background" to true)
        var out = ""
        repeat(30) { out = call("process_output", "id" to "bg1"); if ("stopped" in out) return@repeat; delay(100) }
        assertTrue(out.startsWith("[bg1 stopped (exit 0)]") && "done" in out, out)
    }

    @Test
    fun closeKillsBackgroundProcesses() = runBlocking {
        call("shell", "command" to "sleep 30", "background" to true)
        val p = backend.backgroundProcess("bg1")
        backend.close()
        assertTrue(!backend.status(p, 100).running)
    }
}
