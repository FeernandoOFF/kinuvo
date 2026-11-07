package kinuvo.harness.core.agenttools

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.ApprovalPolicy
import kinuvo.harness.core.TrustRulesRepository
import kinuvo.harness.core.Verdict
import kinuvo.harness.core.plugin.PluginTools
import kinuvo.harness.core.plugin.ownedBy
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.policyKey
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeManagement : AgentManagement {
    override var toolGroups = setOf("memory", "web_fetch", "skills", "agent_manage")
    val updates = mutableListOf<UpdateAgentParams>()
    val created = mutableListOf<CreateAgentParams>()
    val configured = mutableListOf<SessionSettings>()
    val saved = mutableListOf<SaveMcpParams>()
    val removed = mutableListOf<McpParams>()
    val logins = mutableListOf<McpLoginCompleteParams>()
    var asked: Pair<String, List<Question>>? = null
    var answer: Map<String, List<String>>? = mapOf("address" to listOf(" http://localhost:33418/callback?code=1 "))
    var details = AgentDetails(AgentInfo("ada", "Ada", "none"), AgentSettings("gpt", "medium", "default", "none", tools = listOf("memory", "agent_manage"), provider = "codex"), "# Ada\n\nPlans trips.", "Be kind.")
    var session = SessionInfo("s1", "ada", "Chat")
    var servers = mutableListOf(McpServerInfo("notes", url = "https://notes.example/mcp"), McpServerInfo("shared-wiki", ConfigScope.Shared, url = "https://wiki.example/mcp"))
    var rejectSession: String? = null
    var test = McpTestResult(listOf("search", "read"))
    var agentIds = listOf("ada", "bob")

    override suspend fun agents() = agentIds.map { AgentInfo(it, it, "none") }
    override suspend fun details(agentId: String) = details
    override suspend fun update(p: UpdateAgentParams): AgentDetails { updates += p; return details }
    override suspend fun create(p: CreateAgentParams): AgentInfo { created += p; return AgentInfo(p.agentId, p.name ?: p.agentId, "none") }
    override suspend fun session(agentId: String, sessionId: String) = session
    override suspend fun check(agentId: String, sessionId: String, settings: SessionSettings) { rejectSession?.let { throw IllegalArgumentException(it) } }
    override suspend fun configure(agentId: String, sessionId: String, settings: SessionSettings): SessionInfo { check(agentId, sessionId, settings); configured += settings; return session }
    override suspend fun mcpServers(agentId: String) = servers.toList()
    override suspend fun saveMcp(p: SaveMcpParams): McpServerInfo { saved += p; return p.server }
    override suspend fun removeMcp(p: McpParams) { removed += p }
    override suspend fun testMcp(p: McpParams) = test
    override suspend fun loginMcp(p: McpLoginParams) = "https://auth.example/authorize?state=x"
    override suspend fun completeMcpLogin(p: McpLoginCompleteParams): McpServerInfo { logins += p; return servers.first() }
    override suspend fun ask(scope: CallerScope, callId: String?, title: String, questions: List<Question>): Map<String, List<String>>? { asked = title to questions; return answer }
}

class AgentManageTest {
    private val management = FakeManagement()
    private val service = AgentManageTools(management)
    private val scope = CallerScope("ada", "s1")

    private fun tool(name: String, hidden: Boolean = false): Tool = service.tools(scope, hidden).first { it.descriptor.name == name }

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    }

    private suspend fun run(name: String, vararg pairs: Pair<String, Any>) = tool(name).execute(args(*pairs))

    private suspend fun described(name: String, vararg pairs: Pair<String, Any>) = tool(name).describe(args(*pairs))!!

    @Test fun readToolsAreLowAndTheRestAskAtTheirRisk() {
        val tools = service.tools(scope).associateBy { it.descriptor.name }
        assertEquals(setOf("agent_get", "agent_update", "agent_create", "mcp_list", "mcp_add", "mcp_remove", "mcp_test", "mcp_login", "session_configure"), tools.keys)
        assertEquals(setOf("agent_get", "mcp_list", "mcp_test"), tools.filterValues { it.risk == Risk.Low }.keys)
        assertEquals(setOf("mcp_login", "session_configure"), tools.filterValues { it.risk == Risk.Medium }.keys)
        assertEquals(setOf("agent_update", "agent_create", "mcp_add", "mcp_remove"), tools.filterValues { it.risk == Risk.High }.keys)
        assertTrue(tools.getValue("mcp_remove").descriptor.description.contains("Only when the user named"))
    }

    @Test fun changesAlwaysAskEvenAtFullToleranceAndCannotBeTrusted() = runTest {
        val plain = service.tools(scope)
        val bound = plain.map { it.descriptor.name }.zip(PluginTools(plain).ownedBy(PluginInstanceId("kinuvo.core")).tools).toMap()
        val changing = listOf("agent_update", "agent_create", "mcp_add", "mcp_remove")
        val rules = object : TrustRulesRepository {
            override suspend fun get(agentId: String) = TrustRules(trusted = changing.map { bound.getValue(it).policyKey })
            override suspend fun update(agentId: String, transform: (TrustRules) -> TrustRules) {}
        }
        val policy = ApprovalPolicy(Agent("ada", "Ada", AgentConfig(approvals = "full")), rules)
        changing.forEach { name ->
            val verdict = policy.decide(bound.getValue(name), args("name" to "x").toString())
            assertTrue(verdict is Verdict.Ask, name)
            assertTrue((verdict as Verdict.Ask).trustKeys.isEmpty(), name)
        }
        assertEquals(Verdict.Run, policy.decide(bound.getValue("agent_get")))
    }

    @Test fun agentGetShowsOwnSettingsWithoutRulesOrMemory() = runTest {
        val text = run("agent_get")
        assertTrue("ada \"Ada\"" in text && "codex/gpt" in text && "Plans trips." in text && "Be kind." in text)
        assertTrue("memory, agent_manage" in text && "notes, shared-wiki" in text)
        assertTrue("only the user changes it" in text)
    }

    @Test fun agentUpdateChangesOwnFieldsAndShowsBeforeAndAfter() = runTest {
        val a = arrayOf<Pair<String, Any>>("name" to "Ada L", "soul" to "Be brief.", "model" to "gpt-6", "provider" to "openai", "reasoning" to "high", "enable_tools" to "web_fetch, skills", "disable_tools" to "memory")
        val text = described("agent_update", *a)
        assertTrue("Name: Ada → Ada L" in text, text)
        assertTrue("Soul: \"Be kind.\" (8 characters) → \"Be brief.\" (9 characters)" in text || "Soul: Be kind. → Be brief." in text, text)
        assertTrue("Model: codex/gpt → openai/gpt-6" in text, text)
        assertTrue("Reasoning: medium → high" in text, text)
        assertTrue("Tool groups: memory, agent_manage → agent_manage, web_fetch, skills" in text, text)
        assertTrue(management.updates.isEmpty())
        run("agent_update", *a)
        val p = management.updates.single()
        assertEquals(UpdateAgentParams("ada", name = "Ada L", model = "gpt-6", reasoning = "high", soul = "Be brief.", provider = "openai", tools = listOf("agent_manage", "web_fetch", "skills")), p)
        assertNull(p.approvals); assertNull(p.rules); assertNull(p.exec); assertNull(p.workspace)
    }

    @Test fun agentUpdateKeepsTheNameWhenOnlyIdentityChanges() = runTest {
        run("agent_update", "identity" to "# Ada\n\nNow a chef.")
        assertEquals("Ada", management.updates.single().name)
        assertEquals("# Ada\n\nNow a chef.", management.updates.single().identity)
    }

    @Test fun agentUpdateWithNothingNewChangesNothing() = runTest {
        assertEquals("Changes to ada: no changes.", described("agent_update", "name" to "Ada"))
        assertEquals("Nothing to change.", run("agent_update", "name" to "Ada"))
        assertTrue(management.updates.isEmpty())
    }

    @Test fun agentUpdateCannotRaiseRiskToleranceOrTouchTrustAndDenyRules() = runTest {
        listOf("approvals" to "full", "risk_tolerance" to "full", "rules" to "{}", "trust" to "shell", "trusted" to "shell", "deny" to "", "exec" to "host", "workspace" to "/").forEach { (key, value) ->
            val a = if (value.isEmpty()) buildJsonObject { put(key, buildJsonArray { add("x") }) } else args(key to value)
            val e = assertFailsWith<IllegalArgumentException>(key) { tool("agent_update").describe(a) }
            assertTrue("only be changed by the user, in Settings" in e.message!!, key)
            assertFailsWith<IllegalArgumentException>(key) { tool("agent_update").execute(a) }
        }
        assertTrue(management.updates.isEmpty())
    }

    @Test fun agentUpdateCannotRemoveADenyRuleByReplacingRules() = runTest {
        assertFailsWith<IllegalArgumentException> { run("agent_update", "rules" to """{"deny":{"tools":[],"shell":[]}}""") }
        assertTrue(management.updates.isEmpty())
    }

    @Test fun agentUpdateCannotEnableAToolGroupTheServerLacks() = runTest {
        val e = assertFailsWith<IllegalArgumentException> { described("agent_update", "enable_tools" to "computer") }
        assertTrue("off on this Server" in e.message!! && "Settings" in e.message!!)
        assertFailsWith<IllegalArgumentException> { run("agent_update", "enable_tools" to "computer") }
        assertFailsWith<IllegalArgumentException> { run("agent_update", "enable_tools" to "made_up") }
        assertTrue(management.updates.isEmpty())
        management.toolGroups += "computer"
        run("agent_update", "enable_tools" to "computer")
        assertEquals(listOf("memory", "agent_manage", "computer"), management.updates.single().tools)
    }

    @Test fun agentUpdateCanTurnGroupsOffEvenOnesTheServerLacks() = runTest {
        management.toolGroups = emptySet()
        run("agent_update", "disable_tools" to "memory")
        assertEquals(listOf("agent_manage"), management.updates.single().tools)
    }

    @Test fun agentCreateMakesABlankAgentWithAFreeIdAndCopiesNothing() = runTest {
        val text = described("agent_create", "name" to "Bob", "soul" to "Terse.")
        assertTrue("Bob (bob-2), blank" in text && "no Memory, Skills, MCP servers or rules" in text && "Soul: (none) → Terse." in text, text)
        assertTrue(management.created.isEmpty())
        run("agent_create", "name" to "Bob", "soul" to "Terse.", "model" to "gpt-6", "provider" to "openai")
        assertEquals(CreateAgentParams("bob-2", "Bob", cloneFrom = null), management.created.single())
        assertEquals(UpdateAgentParams("bob-2", name = "Bob", soul = "Terse.", provider = "openai", model = "gpt-6"), management.updates.single())
    }

    @Test fun agentCreateWithoutExtrasMakesNoSecondUpdate() = runTest {
        run("agent_create", "name" to "Fresh Helper!")
        assertEquals("fresh-helper", management.created.single().agentId)
        assertTrue(management.updates.isEmpty())
    }

    @Test fun mcpListShowsOwnAndSharedServers() = runTest {
        val text = run("mcp_list")
        assertTrue("- notes [https://notes.example/mcp] agent" in text && "- shared-wiki [https://wiki.example/mcp] shared" in text, text)
    }

    @Test fun mcpAddSavesAnAgentScopedServerAndHidesValues() = runTest {
        val a = buildJsonObject {
            put("name", "files"); put("command", "npx")
            put("args", buildJsonArray { add("-y"); add("files-mcp") })
            put("env", buildJsonObject { put("TOKEN", "super-secret-value") })
        }
        val text = tool("mcp_add").describe(a)!!
        assertTrue("MCP server: (none) → files [npx -y files-mcp]" in text, text)
        assertTrue("Environment: (none) → TOKEN" in text && "super-secret-value" !in text && "Values are not shown" in text, text)
        tool("mcp_add").execute(a)
        val server = management.saved.single().server
        assertEquals(ConfigScope.Agent, server.scope)
        assertEquals(listOf("-y", "files-mcp"), server.args)
        assertEquals(mapOf("TOKEN" to "super-secret-value"), server.env)
    }

    @Test fun mcpAddNeedsExactlyOneOfUrlOrCommandAndShowsAReplacement() = runTest {
        assertFailsWith<IllegalArgumentException> { run("mcp_add", "name" to "x") }
        assertFailsWith<IllegalArgumentException> { run("mcp_add", "name" to "x", "url" to "https://a", "command" to "b") }
        assertTrue("MCP server: notes [https://notes.example/mcp] → notes [https://new.example/mcp]" in described("mcp_add", "name" to "notes", "url" to "https://new.example/mcp"))
        assertTrue(management.saved.isEmpty())
    }

    @Test fun mcpAddKeepsPasswordManagerReferencesButRejectsSessionSecrets() = runTest {
        fun server(token: String) = buildJsonObject {
            put("name", "files"); put("url", "https://files.example/mcp")
            put("headers", buildJsonObject { put("Authorization", "Bearer $token") })
        }
        tool("mcp_add").execute(server("\${pass://Kinuvo/Files/token}"))
        assertEquals(mapOf("Authorization" to "Bearer \${pass://Kinuvo/Files/token}"), management.saved.single().server.headers)
        val typed = server("\${secret://abc}")
        val e = assertFailsWith<IllegalArgumentException> { tool("mcp_add").describe(typed) }
        assertTrue("password manager" in e.message!!, e.message)
        assertFailsWith<IllegalArgumentException> { tool("mcp_add").execute(typed) }
        assertEquals(1, management.saved.size)
    }

    @Test fun mcpRemoveOnlyRemovesOwnServersAndShowsWhat() = runTest {
        assertTrue("MCP server: notes [https://notes.example/mcp] → (removed)" in described("mcp_remove", "name" to "notes"))
        run("mcp_remove", "name" to "notes")
        assertEquals(McpParams("ada", "notes", ConfigScope.Agent), management.removed.single())
        val e = assertFailsWith<IllegalArgumentException> { run("mcp_remove", "name" to "shared-wiki") }
        assertTrue("Settings" in e.message!!)
        assertEquals(1, management.removed.size)
    }

    @Test fun mcpTestReportsToolsFailuresAndSignInNeeded() = runTest {
        assertEquals("'notes' works: 2 tools: search, read", run("mcp_test", "name" to "notes"))
        management.test = McpTestResult(error = "401", needsLogin = true)
        assertTrue("use mcp_login" in run("mcp_test", "name" to "notes"))
        management.test = McpTestResult(error = "refused")
        assertEquals("'notes' failed: refused", run("mcp_test", "name" to "notes"))
    }

    @Test fun mcpLoginShowsTheLinkInAFormThenCompletesWithThePastedAddress() = runTest {
        assertEquals("Signed in to 'notes'.", tool("mcp_login").execute(args("name" to "notes"), "call-1"))
        val (title, questions) = management.asked!!
        assertEquals("Sign in to notes", title)
        assertTrue("https://auth.example/authorize?state=x" in questions.single().label)
        assertEquals(listOf(McpLoginCompleteParams("http://localhost:33418/callback?code=1")), management.logins)
    }

    @Test fun mcpLoginSkippedLeavesTheServerSignedOut() = runTest {
        management.answer = null
        assertEquals("The user skipped the sign-in.", run("mcp_login", "name" to "notes"))
        assertTrue(management.logins.isEmpty())
    }

    @Test fun sessionConfigureChangesTheModelOfThisSessionOnlyAndShowsIt() = runTest {
        val text = described("session_configure", "model" to "gpt-6", "provider" to "openai", "reasoning" to "high")
        assertTrue("Model: codex/gpt → openai/gpt-6" in text && "Reasoning: medium → high" in text, text)
        run("session_configure", "model" to "gpt-6", "provider" to "openai", "reasoning" to "high")
        assertEquals(SessionSettings(model = "gpt-6", reasoning = "high", provider = "openai"), management.configured.single())
        assertTrue(management.updates.isEmpty())
    }

    @Test fun sessionConfigureCannotRaiseRiskToleranceButMayLowerIt() = runTest {
        val e = assertFailsWith<IllegalArgumentException> { described("session_configure", "approvals" to "full") }
        assertTrue("can't be raised from a chat" in e.message!!)
        assertFailsWith<IllegalArgumentException> { run("session_configure", "approvals" to "relaxed") }
        assertTrue(management.configured.isEmpty())
        management.session = management.session.copy(settings = SessionSettings(approvals = "relaxed"))
        assertFailsWith<IllegalArgumentException> { run("session_configure", "approvals" to "full") }
        run("session_configure", "approvals" to "strict")
        assertEquals("strict", management.configured.single().approvals)
        run("session_configure", "approvals" to "relaxed")
        assertEquals(1, management.configured.size)
    }

    @Test fun sessionConfigureIsRefusedBeforeAskingWhenTheServerRejectsTheSettings() = runTest {
        management.rejectSession = "A Hidden Session needs an on-device model"
        assertFailsWith<IllegalArgumentException> { described("session_configure", "model" to "gpt-6", "provider" to "openai") }
        assertFailsWith<IllegalArgumentException> { run("session_configure", "model" to "gpt-6", "provider" to "openai") }
        assertTrue(management.configured.isEmpty())
    }

    @Test fun aHiddenSessionOnlyGetsSessionConfigure() {
        assertEquals(listOf("session_configure"), service.tools(scope, hidden = true).map { it.descriptor.name })
    }

    @Test fun sessionConfigureNeedsASession() = runTest {
        val e = assertFailsWith<IllegalArgumentException> { service.tools(CallerScope("ada", null)).first { it.descriptor.name == "session_configure" }.execute(args("model" to "x")) }
        assertFalse(e.message.isNullOrBlank())
    }
}
