package kinuvo.harness.codex

import kinuvo.harness.testing.*

import kinuvo.protocol.AgentEvent
import kinuvo.harness.core.AgentRun
import kinuvo.protocol.SessionEntry
import kinuvo.harness.core.store.AgentFiles
import kotlinx.io.files.Path
import kinuvo.harness.core.store.FileStore
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class CodexCompactionLiveTest {
    @Test
    fun compactsAndKeepsFacts() = runBlocking {
        if (System.getenv("KINUVO_LIVE") != "1") return@runBlocking
        val store = FileStore(AgentFiles(Path(Files.createTempDirectory("harness-test").toFile().path)))
        val base = store.agents().single()
        val agent = base.copy(config = base.config.copy(reasoning = "low", compactAtTokens = 500))
        val runner = AgentRun(agent, store, CodexProvider().model(agent.config), emptyList())
        val session = store.createSession(agent.id, "You are terse.")
        runBlocking {
            runner.run(session.id, "My project codename is BLUEFIN. Just say ok.").toList()
            store.append(agent.id, session.id, SessionEntry.AssistantMessage("The project is BLUEFIN. ".repeat(150)))
            val events = runner.run(session.id, "What is my project codename? One word.").toList()
            val answer = events.filterIsInstance<AgentEvent.TextMessageEnd>().joinToString { it.text }
            assertTrue(events.any { it is AgentEvent.Compacted }, "expected compaction, got $events")
            assertTrue("BLUEFIN" in answer.uppercase(), "got: $answer")
            assertTrue(store.entries(agent.id, session.id).any { it is SessionEntry.Compaction })
        }
    }
}
