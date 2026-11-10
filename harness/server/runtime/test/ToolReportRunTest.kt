package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentRun
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ToolReportRunTest {
    @Test fun reportQueuesBehindTheActiveRun() = runTest {
        val fixture = RuntimeFixture()
        val session = fixture.transcripts.createSession(fixture.agent.id, "")
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val model = scriptedModel { _, _ -> flow {
            calls++
            if (calls == 1) release.await()
            emit(StreamFrame.End())
        } }
        val runs = Runs(SessionLog(fixture.store), this)
        fun runner() = AgentRun(fixture.agent, fixture.store, model, emptyList())
        val first = runs.start(fixture.agent, session.id, "start", runner = ::runner)
        runCurrent()
        val report = SessionEntry.ToolReport("shell", "call", "exit 0: done")
        val second = async { runs.start(fixture.agent, session.id, "", report = report, queued = true, runner = ::runner) }
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(1, calls)
        release.complete(Unit)
        first.job.join()
        second.await().job.join()
        val entries = fixture.store.entries(fixture.agent.id, session.id)
        assertEquals(report.content, entries.filterIsInstance<SessionEntry.ToolReport>().single().content)
        assertEquals(1, entries.filterIsInstance<SessionEntry.UserMessage>().size)
        assertEquals(2, calls)
    }
}
