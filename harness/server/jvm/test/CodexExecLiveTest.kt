package kinuvo.harness.server

import kinuvo.harness.codex.*

import kinuvo.protocol.AgentEvent
import kinuvo.harness.server.Harness
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexExecLiveTest {
    @Test
    fun writesAndRunsInHostWorkspace() = runBlocking {
        if (System.getenv("KINUVO_LIVE") != "1") return@runBlocking
        val home = Files.createTempDirectory("harness-live-exec").toFile()
        val ws = Files.createTempDirectory("harness-ws").toFile().canonicalFile
        val harness = Harness(Path(home.path))
        val agent = harness.store.agents().single().let {
            it.copy(config = it.config.copy(reasoning = "low", exec = "host", workspace = listOf(ws.path), approvals = "full"))
        }
        val session = harness.newSession(agent)
        val events = runBlocking {
            harness.runner(agent).run(session.id, "Create hello.txt containing exactly 'hi from harness', then run `wc -c hello.txt` and tell me the byte count.").toList()
        }
        val tools = events.filterIsInstance<AgentEvent.ToolCallStart>().map { it.toolCallName }
        assertEquals("hi from harness", File(ws, "hello.txt").readText().trim())
        assertTrue("shell" in tools, "tools: $tools")
        assertTrue(events.filterIsInstance<AgentEvent.TextMessageEnd>().any { "15" in it.text || "16" in it.text }, "$events")
    }
}
