package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.tools.*
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.ToolIdentity
import kinuvo.protocol.DenyRules
import kinuvo.protocol.TrustRules
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class CoreToolRegistryTest {
    private fun tool(name: String) = object : CoreTool {
        override val identity = ToolIdentity(PluginInstanceId("kinuvo.core"), name)
        override val descriptor = ToolDescriptor(name, name)
        override suspend fun execute(args: JsonObject) = name
    }

    @Test fun scopeNamedToolsWinTheCap() {
        val policy = ModelProfile.of(kinuvo.protocol.ModelTier.Small).tools.copy(cap = 1)
        val selected = policy.select(listOf(tool("memory"), tool("schedule")), ToolScope(names = setOf("schedule"), groups = setOf(ToolGroup.Core)))
        assertEquals(listOf("schedule"), selected.map { it.descriptor.name })
    }

    @Test fun externalNamesAndInternalClaimsDoNotGrantScope() {
        val external = object : Tool {
            override val identity = ToolIdentity(PluginInstanceId("mcp.test"), "memory")
            override val group = ToolGroup.Internal
            override val descriptor = ToolDescriptor("memory", "External memory")
            override suspend fun execute(args: JsonObject) = "OK"
        }
        assertFalse(ToolScope.only("memory").allows(external))
        assertTrue(ToolScope.only(external.identity.key).allows(external))
        assertTrue(ModelProfile.of(kinuvo.protocol.ModelTier.Micro).tools.select(listOf(external)).isEmpty())
        val other = object : Tool {
            override val identity = ToolIdentity(PluginInstanceId("mcp.other"), "memory")
            override val descriptor = ToolDescriptor("memory", "Other memory")
            override suspend fun execute(args: JsonObject) = "OK"
        }
        val keyed = ToolScope(names = setOf(external.identity.key), ask = setOf(external.identity.key))
        assertTrue(keyed.mustAsk(external))
        assertFalse(ToolScope(ask = setOf(external.identity.key)).mustAsk(other))
        assertTrue(ToolScope.All.child(keyed).mustAsk(external))
        assertEquals(listOf("device_tools"), Toolsets.legacyGroups("device_tools"))
        assertTrue("device_tools" in Toolsets.legacyGroups("data"))
    }

    @Test fun metadataAndLegacyNames() {
        val entries = CoreToolRegistry.entries
        assertEquals(entries.size, entries.map { it.name }.distinct().size)
        entries.forEach {
            val tool = tool(it.name)
            assertEquals(it.group, tool.group)
            assertEquals(it.risk, tool.risk)
            assertEquals(it.kind, tool.kind)
            assertEquals(it.repeatable, tool.repeatable)
            assertTrue(it.summary.isNotBlank())
        }
        ToolNames.OPTIONAL.forEach { name ->
            assertTrue(CoreToolRegistry[name] != null || Toolsets.Core.contains(name))
        }
        assertEquals(setOf("web_fetch", "web_search"), Toolsets.Core.resolve("web_fetch"))
        assertTrue(ToolScope.only("skills").allows(tool("activate_skill")))
        val plugin = object : Tool {
            override val descriptor = ToolDescriptor("memory", "Plugin tool")
            override suspend fun execute(args: JsonObject) = ""
        }
        assertEquals(ToolGroup.External, plugin.group)
    }

    @Test fun legacyAgentYamlRoundTrips() = runTest {
        val home = TestHome()
        val legacy = home.agent.config.copy(tools = ToolNames.OPTIONAL)
        home.store.saveConfig(home.agent, legacy)
        assertEquals(ToolNames.OPTIONAL, home.store.agents().single().config.tools)
    }

    @Test fun includesAndCycles() {
        val sets = Toolsets(listOf(Toolset("a", setOf("memory")), Toolset("b", setOf("shell"), setOf("a"))))
        assertEquals(setOf("memory", "shell"), sets.resolve("b"))
        assertFailsWith<IllegalArgumentException> { Toolsets(listOf(Toolset("a", includes = setOf("b")), Toolset("b", includes = setOf("a")))) }
        assertFailsWith<IllegalArgumentException> { Toolsets(listOf(Toolset("a", includes = setOf("missing")))) }
    }

    @Test fun childIntersectsAndInheritsRestrictions() {
        val parent = ToolScope(toolsets = setOf("web"), ceiling = Tolerance.Strict, ask = setOf("web_fetch"))
        val child = parent.child(ToolScope.only("web_fetch", "shell").copy(ceiling = Tolerance.Full))
        assertTrue(child.allows(tool("web_fetch")))
        assertFalse(child.allows(tool("shell")))
        assertFalse(child.allows(tool("web_search")))
        val nested = child.child(ToolScope.All).child(ToolScope.only("shell", "web_fetch"))
        assertFalse(nested.allows(tool("shell")))
        assertEquals(Tolerance.Strict, nested.tolerance(Tolerance.Full))
        assertTrue(nested.mustAsk(tool("web_fetch")))
        assertEquals(Tolerance.Strict, child.tolerance(Tolerance.Full))
        assertTrue(child.mustAsk(tool("web_fetch")))
        assertEquals(Tolerance.Default, ToolScope(ceiling = Tolerance.Full).tolerance(Tolerance.Default))
    }

    @Test fun tighteningBeatsTrust() = runTest {
        val home = TestHome()
        val tool = tool("web_fetch")
        val agent = home.agent.copy(config = home.agent.config.copy(approvals = "full"))
        home.trustRules.update(agent.id) { TrustRules(trusted = listOf(tool.policyKey)) }
        assertIs<Verdict.Run>(ApprovalPolicy(agent, home.trustRules).decide(tool))
        assertIs<Verdict.Ask>(ApprovalPolicy(agent, home.trustRules, scope = ToolScope(ceiling = Tolerance.Strict)).decide(tool))
        assertIs<Verdict.Ask>(ApprovalPolicy(agent, home.trustRules, scope = ToolScope(ask = setOf("web_fetch"))).decide(tool))
        val child = ToolScope(ask = setOf("web_fetch")).child(ToolScope.only("web_fetch"))
        assertIs<Verdict.Ask>(ApprovalPolicy(agent, home.trustRules, scope = child).decide(tool))
        assertIs<Verdict.Block>(ApprovalPolicy(agent, home.trustRules, scope = ToolScope.only("shell")).decide(tool))
        home.trustRules.update(agent.id) { it.copy(deny = DenyRules(tools = listOf(tool.policyKey))) }
        assertIs<Verdict.Block>(ApprovalPolicy(agent, home.trustRules, scope = child).decide(tool))
        val ask = tool(ToolNames.ASK_USER)
        assertIs<Verdict.Run>(ApprovalPolicy(agent, home.trustRules, scope = ToolScope(ceiling = Tolerance.Strict, ask = setOf(ToolNames.ASK_USER))).decide(ask))
    }

    @Test fun capabilitiesUseRegistryHints() {
        val home = TestHome()
        val text = capabilitiesFor(home.agent, listOf(tool("memory")))
        assertContains(text, CoreToolRegistry["memory"]!!.hint!!)
        assertFalse(text.contains(CoreToolRegistry["schedule"]!!.hint!!))
    }
}
