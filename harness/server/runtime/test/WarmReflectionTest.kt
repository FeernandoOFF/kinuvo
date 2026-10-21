package kinuvo.harness.server

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.AgentModel
import kinuvo.protocol.RequestKind
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
        data class Input(val instructions: String, val history: List<Message>, val tools: List<ToolDescriptor>)
        val configs = mutableListOf<AgentConfig>()
        val seen = mutableListOf<Input>()
        val factory: (AgentConfig) -> AgentModel = { config ->
            configs += config
            scriptedModel { prompt, tools ->
                val instructions = prompt.messages.filterIsInstance<Message.System>().firstOrNull()?.textContent().orEmpty()
                val history = prompt.messages
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
        assertTrue(parent.history.filterIsInstance<Message.System>().any { "Fresh memory" in it.textContent() })
        val history = harness.store.entries(agent, session.id)
        val result = harness.reflect(agent, session.id, history, history)
        assertTrue(result.warmContext)
        assertEquals(1, configs.size)
        assertEquals(parent.instructions, seen.last().instructions)
        assertEquals(parent.tools, seen.last().tools)
        assertEquals(parent.history, seen.last().history.take(parent.history.size))
        assertEquals(3, result.measurements.size)
        assertTrue(result.measurements.all { it.model == "parent-model" && it.reasoning == "high" && it.kind == RequestKind.Reflection })
        seen.drop(1).forEach {
            assertEquals(parent.instructions, it.instructions)
            assertEquals(parent.tools, it.tools)
            assertEquals(parent.history, it.history.take(parent.history.size))
        }
        harness.close()

        val restarted = HarnessRuntime(ServerInstallation.assemble(AgentFiles(home)), modelFactory = factory)
        assertFalse(restarted.reflect(agent, session.id, history, history).warmContext)
        restarted.close()
    }
}
