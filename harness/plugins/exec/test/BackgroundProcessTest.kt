package kinuvo.harness.exec

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class BackgroundProcessTest {
    private val backend = HostBackend(listOf(Files.createTempDirectory("bg").toFile()))
    private val tools = execTools(backend).associateBy { it.descriptor.name }
    private suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = tools.getValue(tool).execute(buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    })

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
