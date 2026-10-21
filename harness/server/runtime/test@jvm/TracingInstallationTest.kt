package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.store.*
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.AgentEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.uuid.Uuid

class TracingInstallationTest {
    private val files = AgentFiles(Path(SystemTemporaryDirectory, "tracing-installation-${Uuid.random()}"))
    @AfterTest
    fun cleanup() = deleteTree(files.home)

    @Test
    fun installationTracesChatAndReflectionByDefault() = runBlocking {
        val runtime = HarnessRuntime(ServerInstallation.assemble(files), modelFactory = {
            scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("private-response-secret"), StreamFrame.End()) }
        })
        try {
            val agent = runtime.store.agents().single()
            val session = runtime.newSession(agent)
            assertIs<AgentEvent.RunFinished>(runtime.runner(agent, session.id).run(session.id, "private-input-secret").toList().last())
            val history = runtime.store.entries(agent, session.id)
            assertNull(runtime.reflect(agent, session.id, history).error)
            val content = files.traces.children().filter { it.name.endsWith(".jsonl") }.joinToString("") { it.readText() }
            assertTrue("invoke_agent ${agent.id}" in content)
            assertTrue("chat scripted" in content)
            assertTrue("Chat" in content)
            assertTrue("Reflection" in content)
            assertTrue(session.id in content)
            assertFalse("private-" in content)
        } finally { runtime.close() }
    }

    @Test
    fun configCanDisableTracingWithoutCreatingTraceFiles() = runBlocking {
        files.updateConfig { it.copy(tracing = false) }
        val dependencies = ServerInstallation.assemble(files)
        val runtime = HarnessRuntime(dependencies, modelFactory = { scriptedModel { _, _ -> flowOf(StreamFrame.End()) } })
        try {
            assertNull(dependencies.tracing)
            val agent = runtime.store.agents().single()
            val session = runtime.newSession(agent)
            assertIs<AgentEvent.RunFinished>(runtime.runner(agent, session.id).run(session.id, "go").toList().last())
            assertFalse(files.traces.exists())
            assertFalse(files.config().tracing)
        } finally { runtime.close() }
    }
}
