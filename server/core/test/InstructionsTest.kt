package kinuvo.server

import kinuvo.server.plugin.toolSpec
import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.AgentDoc
import kinuvo.server.plugin.CoreTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.capability.CapabilityState
import kinuvo.protocol.capability.ResolvedTool

class InstructionsTest {
    private fun tool(name: String) = object : CoreTool {
        override val descriptor = toolSpec(name, "test")
        override suspend fun execute(args: JsonObject) = error("Not executed")
    }

    @Test fun smallPromptsMatchOriginalBytes() = runTest {
        val fixture = RuntimeFixture()
        val agent = fixture.agent.copy(name = "Ada")
        fixture.documents[AgentDoc.UserNotes] = "  Likes tea  "
        fixture.documents[AgentDoc.Memory] = "  Lives in Dublin  "
        fixture.documents[AgentDoc.Identity] = "  Calm and curious  "
        val prefix = "You are Ada, the user's personal assistant. Keep replies short and friendly.\n\n"
        val suffix = "What you know about the user:\nLikes tea\nLives in Dublin\n\n" +
            "Tools: memory, web_fetch. Use a tool when the user asks you to do something, and only say it's done after the tool succeeds.\nToday is 2026-11-19.\n"
        val tools = listOf(tool("memory"), tool("web_fetch"))
        assertEquals(prefix + suffix, basicContextFor(agent, fixture.docs, tools, "2026-11-19"))
        assertEquals(prefix + "Who you are:\nCalm and curious\n\n" + suffix,
            basicContextFor(agent, fixture.docs, tools, "2026-11-19", identity = true))
    }

    @Test fun compactBriefAndDocsFollowAdvertisedTools() = runTest {
        val fixture = RuntimeFixture()
        fixture.documents[AgentDoc.Memory] = "A lesson"
        fixture.documents[AgentDoc.UserNotes] = "Likes tea"
        val brief = compactContextFor(fixture.agent, fixture.docs)
        assertTrue("Never claim success without a tool result" in brief)
        assertTrue("missing decision materially changes the work" in brief)
        assertTrue("Keep replies short" in brief)
        assertTrue("A lesson" in brief && "Likes tea" in brief)
        val empty = brief + capabilitiesFor(fixture.agent, emptyList(), compact = true)
        assertFalse("Kinuvo documentation:" in empty)
        assertFalse("Reflection" in empty || "agent_update" in empty || "proactiv" in empty)
        val web = brief + capabilitiesFor(fixture.agent, listOf(tool("web_fetch")), compact = true)
        assertTrue("Kinuvo documentation: $KINUVO_DOCS_URL. Use web_fetch" in web)
        assertTrue("Kinuvo documentation:" in capabilitiesFor(fixture.agent, listOf(tool("web_fetch"))))
        assertFalse("Kinuvo documentation:" in capabilitiesFor(fixture.agent, emptyList()))
        val missing = listOf(ResolvedTool("computer_capture", state = CapabilityState.needsSetup("Pair a Computer", "guides/computer-use/")))
        assertTrue("fetch its page" in capabilitiesFor(fixture.agent, listOf(tool("web_fetch")), missing, compact = true))
    }

    @Test fun aboutKinuvoOnlyOffersPresentConfigurationTools() {
        val absent = aboutKinuvoFor(emptyList())
        assertTrue("## About Kinuvo" in absent)
        assertTrue("Settings → Agent → Model" in absent && "Settings → MCP" in absent)
        assertFalse("Use agent_update" in absent || "Use mcp_add" in absent)
        val partial = aboutKinuvoFor(listOf(tool("agent_get")))
        assertTrue("Use agent_get" in partial)
        assertFalse("Use agent_create" in partial)
        val full = aboutKinuvoFor(listOf("agent_get", "agent_update", "agent_create", "mcp_add", "session_configure").map(::tool))
        assertTrue("Say what you will change" in full && "Approvals show the change" in full)
        assertTrue("Chat never loosens safety" in full)
        assertFalse("Settings → Agent" in full || "Settings → MCP" in full)
        assertTrue("Never answer questions about Kinuvo from memory" in full)
    }

    @Test
    fun operatingRulesApplyWithoutReplacingCustomSoulOrFreezingChangingContext() = runTest {
        val home = TestHome()
        home.docs.write(home.agent.id, AgentDoc.Soul, "Speak in a calm, measured tone.")
        home.docs.write(home.agent.id, AgentDoc.Memory, "First lesson")
        val initial = instructionsFor(home.agent, home.docs)
        val session = home.store.createSession(home.agent.id, initial)
        home.docs.write(home.agent.id, AgentDoc.Memory, "Updated lesson")
        assertEquals(initial, instructionsFor(home.agent.copy(config = home.agent.config.copy(tools = emptyList())), home.docs))
        assertEquals(initial, home.store.entries(home.agent.id, session.id).filterIsInstance<kinuvo.protocol.session.SessionEntry.Started>().single().instructions)
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
        val agent = Agent(AgentId("a"), "A", AgentConfig())
        fun tool(name: String) = object : CoreTool {
            override val descriptor = toolSpec(name, "test")
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
