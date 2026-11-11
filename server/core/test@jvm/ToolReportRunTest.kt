package kinuvo.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.builtin.NativeExecutionFactory
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ModelProfile
import kinuvo.harness.ModelSelection
import kinuvo.harness.NativeHarnessHost
import kinuvo.harness.NativeReflection
import kinuvo.harness.NativeRun
import kinuvo.harness.RunKind
import kinuvo.protocol.model.ModelTier
import kinuvo.server.tools.ToolService
import kinuvo.testing.harness.scriptedModel
import kinuvo.protocol.session.SessionEntry
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
        val service = ToolService(emptyList())
        val host = object : NativeHarnessHost {
            override suspend fun run(agentId: String, sessionId: String, input: ExecutionInput) =
                NativeRun(fixture.agent, fixture.store, ModelSelection(fixture.agent.config, null), service, service, profile = ModelProfile.of(ModelTier.Frontier))
            override suspend fun reflection(agentId: String, sessionId: String): NativeReflection = error("No Reflection")
        }
        val runs = Runs(SessionLog(fixture.store), this, NativeExecutionFactory(host, modelFactory = { model }))
        val first = runs.start(fixture.agent, session.id, ExecutionInput("start"))
        runCurrent()
        val report = SessionEntry.ToolReport("shell", "call", "exit 0: done")
        val second = async { runs.start(fixture.agent, session.id, ExecutionInput("", kind = RunKind.Chat(report = report)), queued = true) }
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
