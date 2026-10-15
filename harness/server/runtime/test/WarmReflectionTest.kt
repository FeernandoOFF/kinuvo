package kinuvo.harness.server

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.Model
import kinuvo.protocol.RequestKind
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.uuid.Uuid
import kinuvo.harness.core.store.AgentFiles
import kotlin.test.*

class WarmReflectionTest {
    private val home = Path(SystemTemporaryDirectory, "warm-reflection-${Uuid.random()}")
    @kotlin.test.AfterTest
    fun cleanup() = kinuvo.harness.core.store.deleteTree(home)
    @Test
    fun reflectionReusesSessionModelAndExactParentInputsAndRestartFallsBack() = runBlocking {
        data class Input(val instructions: String, val history: List<SessionEntry>, val tools: List<ToolDescriptor>)
        val configs = mutableListOf<AgentConfig>()
        val seen = mutableListOf<Input>()
        val factory: (AgentConfig) -> Model = { config ->
            configs += config
            Model { instructions, history, tools ->
                seen += Input(instructions, history, tools)
                flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
            }
        }
        val harness = HarnessRuntime(ServerInstallation.assemble(AgentFiles(home)), modelFactory = factory)
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent)
        harness.configure(agent, session.id, SessionSettings(model = "parent-model", reasoning = "high"))
        harness.docs.write(agent.id, AgentDoc.Memory, "Fresh memory")
        harness.runner(agent, session.id).run(session.id, "Remember my preference for short replies").collect {}
        val parent = seen.single()
        assertFalse("Fresh memory" in parent.instructions)
        assertTrue(parent.history.filterIsInstance<SessionEntry.Context>().any { "Fresh memory" in it.content })
        val history = harness.store.entries(agent, session.id)
        val result = harness.reflect(agent, session.id, history, history)
        assertTrue(result.warmContext)
        assertEquals(1, configs.size)
        assertEquals(parent.instructions, seen.last().instructions)
        assertEquals(parent.tools, seen.last().tools)
        assertEquals(parent.history, seen.last().history.take(parent.history.size))
        assertEquals("parent-model", result.measurements.single().model)
        assertEquals("high", result.measurements.single().reasoning)
        assertEquals(RequestKind.Reflection, result.measurements.single().kind)
        harness.close()

        val restarted = HarnessRuntime(ServerInstallation.assemble(AgentFiles(home)), modelFactory = factory)
        assertFalse(restarted.reflect(agent, session.id, history, history).warmContext)
        restarted.close()
    }
}
