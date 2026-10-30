package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.tools.Tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class InstructionsTest {
    @Test
    fun operatingRulesApplyWithoutReplacingCustomSoulOrFreezingChangingContext() = runTest {
        val home = TestHome()
        home.docs.write(home.agent.id, AgentDoc.Soul, "Speak in a calm, measured tone.")
        home.docs.write(home.agent.id, AgentDoc.Memory, "First lesson")
        val initial = instructionsFor(home.agent, home.docs)
        val session = home.store.createSession(home.agent.id, initial)
        home.docs.write(home.agent.id, AgentDoc.Memory, "Updated lesson")
        assertEquals(initial, instructionsFor(home.agent.copy(config = home.agent.config.copy(tools = emptyList())), home.docs))
        assertEquals(initial, home.store.entries(home.agent.id, session.id).filterIsInstance<kinuvo.protocol.SessionEntry.Started>().single().instructions)
        assertTrue(initial.indexOf("## How to work") < initial.indexOf("## Soul"))
        assertTrue("Speak in a calm, measured tone." in initial)
        assertEquals("Speak in a calm, measured tone.", home.docs.read(home.agent.id, AgentDoc.Soul))
        assertFalse("First lesson" in initial)
        val context = personalContextFor(home.agent, home.docs)
        assertTrue("Updated lesson" in context)
        assertFalse("First lesson" in context)
    }

    @Test
    fun skillGuidanceUsesAvailableToolsRatherThanConfiguredOrAssumedTools() {
        val agent = Agent("a", "A", AgentConfig())
        fun tool(name: String) = object : Tool {
            override val descriptor = ToolDescriptor(name, "test")
            override suspend fun execute(args: JsonObject) = error("Not executed")
        }
        val disabled = capabilitiesFor(agent, emptyList())
        assertFalse("activate_skill" in disabled)
        assertFalse("read_skill_file" in disabled)
        assertFalse("skill_manage" in disabled)
        val activationOnly = capabilitiesFor(agent, listOf(tool("activate_skill")))
        assertTrue("activate a matching Skill" in activationOnly)
        assertFalse("read_skill_file" in activationOnly)
        assertFalse("skill_manage" in activationOnly)
        val full = capabilitiesFor(agent, listOf(tool("activate_skill"), tool("read_skill_file"), tool("skill_manage")))
        assertTrue("referenced resources" in full)
        assertTrue("background Reflection" in full)
        assertTrue("normal Approval policy" in full)
    }
}
