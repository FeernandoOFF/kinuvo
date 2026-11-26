package kinuvo.server

import kinuvo.server.plugin.toolSpec
import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.AgentDoc
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.CoreToolRegistry
import kinuvo.protocol.plugin.PluginInstanceId
import kinuvo.protocol.plugin.ToolIdentity
import kinuvo.server.plugin.CoreTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.capability.CapabilityState
import kinuvo.protocol.capability.ResolvedTool

class InstructionsTest {
    private fun tool(name: String, advertisedName: String = name) = object : CoreTool {
        override val metadata get() = CoreToolRegistry[name]!!
        override val descriptor = toolSpec(advertisedName, "test")
        override suspend fun execute(args: JsonObject) = error("Not executed")
    }

    private fun external(readOnly: Boolean = true) = object : Tool {
        override val identity = ToolIdentity(PluginInstanceId("kinuvo.mcp", "external"), "lookup")
        override val descriptor = toolSpec("lookup", "External tool")
        override val risk = if (readOnly) Risk.Low else Risk.Medium
        override suspend fun execute(args: JsonObject) = error("Not executed")
    }

    @Test fun discoveryUsesExternalReadToolsWithoutManagement() {
        val prompt = firstSessionFor(listOf(external()))
        assertTrue("Explore" in prompt && "at most 8" in prompt)
        assertFalse("mcp_list" in prompt)
        for (tools in listOf(emptyList(), listOf(tool("mcp_list")), listOf(external(false)))) {
            assertTrue("Skip look-ups" in firstSessionFor(tools))
            assertFalse("Explore" in firstSessionFor(tools))
        }
    }

    @Test fun firstSessionGuidanceFollowsAdvertisedTools() {
        val optional = listOf("mcp_list", "show_ui", "ask_user", "tasks", "memory", "mcp_add", "mcp_login")
        for (mask in 0 until (1 shl optional.size)) {
            val names = optional.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val text = firstSessionFor(names.map(::tool) + external())
            optional.filter { it !in names }.forEach { assertFalse(it in text, "$it in $names") }
            assertEquals("tasks" in names, "Add to tasks" in text)
            assertTrue("read-only" in text && "at most 8" in text && "Approvals still apply" in text)
            assertTrue("invite corrections" in text && "3–5" in text)
            val followUp = text.indexOf("Do the first suggested read-only follow-up yourself when cheap, within the look-up limit, before presenting anything")
            val recommendation = text.indexOf("lead with what you found and your top recommendation")
            assertTrue(followUp >= 0 && followUp < recommendation && recommendation < text.indexOf("As the last step of the turn, propose 3–5"))
            when {
                "show_ui" in names -> assertTrue("compact show_ui card" in text)
                "ask_user" in names -> assertTrue("with ask_user" in text)
                else -> assertTrue("numbered list" in text)
            }
            assertTrue("If no connected server offers read-only look-up tools, skip look-ups" in text)
            assertTrue("concrete user task first" in text && "connecting apps" in text)
            assertTrue("User Notes may only receive durable facts the user stated or confirmed" in text)
            assertTrue("do not record connector offers or decline/ignore events" in text)
        }
    }

    @Test fun discoveryUsesCoreAliasesAndSuppressesOfferBookkeeping() {
        val external = listOf("mcp_list", "memory", "show_ui", "ask_user", "tasks").map { name ->
            object : Tool {
                override val identity = ToolIdentity(PluginInstanceId("mcp", "external"), name)
                override val descriptor = toolSpec(name, "External tool")
                override suspend fun execute(args: JsonObject) = error("Not executed")
            }
        }
        val untrusted = firstSessionFor(external)
        assertFalse("Explore" in untrusted || "Add to tasks" in untrusted)
        val real = listOf("mcp_list", "memory", "show_ui", "ask_user", "tasks").map { tool(it, "core_$it") }
        val trusted = firstSessionFor(external + real + external())
        assertTrue("Use core_mcp_list" in trusted && "compact core_show_ui card" in trusted)
        assertTrue("using core_tasks only after the user chooses" in trusted)
        assertTrue("with core_ask_user" in firstSessionFor(real.filter { it.descriptor.name != "core_show_ui" } + external()))
        assertFalse("Only after an explicit decline" in aboutKinuvoFor(real, discovery = true))
        assertTrue("Only after an explicit decline" in aboutKinuvoFor(real))
        assertTrue("Settings → Server settings → Tools & MCP → MCP servers" in trusted)
    }

    @Test fun connectorOffersFollowAdvertisedTools() = runTest {
        fun prompt(vararg names: String) = aboutKinuvoFor(names.map { tool(it) })
        val card = prompt("mcp_list", "mcp_add", "mcp_login", "show_ui", "ask_user", "memory")
        assertTrue("available presets" in card && "offer the relevant one once" in card)
        assertTrue("Research the user's request with existing tools without waiting for a connection decision" in card)
        assertTrue("show_ui card with Add / Not now" in card)
        assertFalse("Present the offer with ask_user" in card)
        assertTrue("Only after an explicit decline" in card && "never record silence" in card && "Memory with memory" in card)
        assertTrue("mcp_add(preset) then mcp_login" in card)
        assertTrue("auth/sign-in error" in card && "reconnect via mcp_login" in card)
        val form = prompt("mcp_list", "ask_user")
        assertTrue("Present the offer with ask_user" in form)
        assertTrue("Server settings → Tools & MCP → MCP servers" in form)
        assertFalse("mcp_add(preset)" in form || "mcp_login" in form || "Memory with memory" in form)
        val text = prompt("mcp_list")
        assertTrue("Ask in plain text" in text)
        assertFalse("show_ui" in text || "ask_user" in text)
        assertFalse("available presets" in prompt("mcp_add", "show_ui", "memory"))
        val fixture = RuntimeFixture()
        val tools = listOf("mcp_list", "mcp_add", "mcp_login", "show_ui", "memory").map { tool(it) }
        val compact = compactContextFor(fixture.agent, fixture.docs) + capabilitiesFor(fixture.agent, tools, compact = true)
        assertFalse("available presets" in compact || "offer the relevant one once" in compact)
    }

    @Test fun connectorGuidanceRequiresCoreIdentityAndUsesAdvertisedAliases() {
        val external = listOf("mcp_list", "mcp_add", "mcp_login", "ask_user", "agent_update", "web_fetch").map { name ->
            object : Tool {
                override val identity = ToolIdentity(PluginInstanceId("mcp", "external"), name)
                override val descriptor = toolSpec(name, "External tool")
                override suspend fun execute(args: JsonObject) = error("Not executed")
            }
        }
        val untrusted = aboutKinuvoFor(external)
        assertFalse("available presets" in untrusted || "Use mcp_add" in untrusted || "Use agent_update" in untrusted)
        assertTrue("Personality page" in untrusted)
        val real = listOf("mcp_list", "mcp_add", "mcp_login", "ask_user", "memory", "show_ui", "agent_update", "web_fetch")
            .map { tool(it, "core_$it") }
        val trusted = aboutKinuvoFor(real)
        assertTrue("core_mcp_list's available presets" in trusted)
        assertTrue("core_mcp_add(preset) then core_mcp_login" in trusted)
        assertTrue("core_show_ui card" in trusted && "Memory with core_memory" in trusted)
        assertTrue("Use core_agent_update" in trusted)
        val agent = Agent(AgentId("a"), "A", AgentConfig())
        assertFalse("Kinuvo documentation:" in capabilitiesFor(agent, external))
        assertTrue("Use core_web_fetch" in capabilitiesFor(agent, real))
    }

    @Test fun loginGuidanceFollowsMcpLogin() {
        val withoutLogin = listOf("mcp_list", "mcp_add").map { tool(it) }
        val prompt = aboutKinuvoFor(withoutLogin)
        assertFalse("then mcp_login" in prompt || "reconnect via mcp_login" in prompt)
        assertTrue("Settings → Server settings → Tools & MCP → MCP servers" in prompt)
        val usable = aboutKinuvoFor(withoutLogin + tool("mcp_login"))
        assertTrue("then mcp_login" in usable && "reconnect via mcp_login" in usable)
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
        assertTrue("Resolve retrievable missing information with tools" in brief)
        assertTrue("check current state and changeable facts with tools rather than from memory" in brief)
        assertTrue("If a lookup is unexpectedly empty or partial, try another before concluding" in brief)
        assertTrue("Keep going until the result is complete and verified or genuinely blocked" in brief)
        assertTrue("never end a turn with a promise of future action" in brief)
        assertTrue("A lesson" in brief && "Likes tea" in brief)
        val empty = brief + capabilitiesFor(fixture.agent, emptyList(), compact = true)
        assertFalse("Kinuvo documentation:" in empty)
        assertFalse("Reflection" in empty || "agent_update" in empty || "proactiv" in empty)
        val web = brief + capabilitiesFor(fixture.agent, listOf(tool("web_fetch")), compact = true)
        assertTrue("Kinuvo documentation: $KINUVO_DOCS_URL/llms.txt lists the owner documentation; fetch the matching URL exactly as listed. Use web_fetch" in web)
        assertTrue("Kinuvo documentation:" in capabilitiesFor(fixture.agent, listOf(tool("web_fetch"))))
        assertFalse("Kinuvo documentation:" in capabilitiesFor(fixture.agent, emptyList()))
        val missing = listOf(ResolvedTool("computer_capture", state = CapabilityState.needsSetup("Pair a Computer", "guides/computer-use/")))
        assertTrue("fetch its page" in capabilitiesFor(fixture.agent, listOf(tool("web_fetch")), missing, compact = true))
    }

    @Test fun compactAndFullShareOneProactivityBrief() = runTest {
        val fixture = RuntimeFixture()
        val compact = compactContextFor(fixture.agent, fixture.docs)
        val harness = fixture.runtime()
        val full = try {
            harness.composer.compose(fixture.agent, requireModel = false).context
        } finally { harness.close() }
        val brief = compact.substringAfter("future action.\n").substringBefore("\nKeep replies short.")
        assertEquals(5, brief.lines().size)
        assertTrue(brief.startsWith("When the user names a topic, goal, problem or open-ended request, research first"))
        assertTrue("web search, connected apps, notes and Memory" in brief)
        assertTrue("Research only with relevant read-only calls within the user's scope" in brief)
        assertTrue("keep private data (Memory, notes, connector content) out of web-search queries and other external tool arguments unless the user asked for that disclosure" in brief)
        assertTrue("Answer with findings and a recommendation, not questions or a menu" in brief)
        assertTrue("never end read-only work with \"want me to...?\"" in brief)
        assertTrue("writes, sends, spending or irreversible steps outside the user's authorized scope" in brief)
        assertTrue("follow Kinuvo's Approvals without asking again for authorized work" in brief)
        assertTrue("Ask one focused question" in brief && "after doing the research possible without it" in brief)
        assertFalse("Never answer from memory alone" in brief)
        assertEquals(1, full.windowed(brief.length).count { it == brief })
        assertEquals(1, full.split("When the user names a topic").size - 1)
        val frozen = instructionsFor(fixture.agent, fixture.docs)
        assertFalse("When the user names a topic" in frozen)
        assertTrue("Follow the user's authorized scope and Kinuvo's Approval decisions" in frozen)
    }

    @Test fun aboutKinuvoOnlyOffersPresentConfigurationTools() {
        val absent = aboutKinuvoFor(emptyList())
        assertTrue("## About Kinuvo" in absent)
        assertTrue("selected Agent's Personality page" in absent)
        assertTrue("Server settings → Tools & MCP → Built-in tools" in absent)
        assertTrue("Server settings → Tools & MCP → MCP servers" in absent)
        assertFalse("Use agent_update" in absent || "Use mcp_add" in absent)
        val partial = aboutKinuvoFor(listOf(tool("agent_get")))
        assertTrue("Use agent_get" in partial)
        assertFalse("Use agent_create" in partial)
        val full = aboutKinuvoFor(listOf("agent_get", "agent_update", "agent_create", "mcp_add", "session_configure").map { tool(it) })
        assertTrue("Say what you will change" in full && "Approvals show the change" in full)
        assertTrue("Chat never loosens safety" in full)
        assertFalse("Personality page" in full || "Server settings → Tools & MCP" in full)
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
