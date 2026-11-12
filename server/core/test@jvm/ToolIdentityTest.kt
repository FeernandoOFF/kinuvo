package kinuvo.server

import kinuvo.testing.server.AgentRun
import kinuvo.server.plugin.rawSchemaSpec
import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.Action
import kinuvo.server.plugin.Assessment
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.ToolKind
import kinuvo.server.plugin.ToolRun
import kinuvo.server.plugin.indexedTools
import kinuvo.server.plugin.ownedBy
import kinuvo.server.plugin.policyKey
import kinuvo.server.plugin.withModelNames
import kinuvo.testing.harness.*

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.builtin.tools.*
import kinuvo.server.tools.*
import kinuvo.protocol.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class ToolIdentityTest {
    private val home = TestHome()
    private val owner = PluginInstanceId("example.weather")
    private fun tool(name: String = "forecast", identity: ToolIdentity? = null) = object : Tool {
        override val descriptor = toolSpec(name, "Weather")
        override val identity = identity
        override val displayName = "Get forecast"
        override suspend fun execute(args: JsonObject) = "sunny"
    }
    private fun bound(owner: PluginInstanceId = this.owner, name: String = "forecast", identity: ToolIdentity? = null) =
        PluginTools(listOf(tool(name, identity))).ownedBy(owner).tools.single()

    @Test fun staticManifestsCannotExportUndeclaredTools() {
        val manifest = PluginManifest(PluginDefinition(owner.pluginId, "Weather"), tools = listOf(
            PluginToolDefinition("forecast", "Weather", JsonObject(emptyMap()))))
        assertEquals("forecast", PluginTools(listOf(tool())).ownedBy(owner, manifest).tools.single().identity?.localId)
        assertEquals("forecast", PluginTools(listOf(tool("alias", ToolIdentity(owner, "forecast"))))
            .ownedBy(owner, manifest).catalog.single().identity?.localId)
        assertFailsWith<IllegalArgumentException> { PluginTools(listOf(tool("hidden"))).ownedBy(owner, manifest) }
        assertFailsWith<IllegalArgumentException> { PluginTools(listOf(tool())).ownedBy(owner, manifest.copy(formatVersion = 2)) }
    }

    @Test fun instancesGetDistinctStableAliasesAndKeepDisplayNames() {
        val first = bound()
        val second = bound(owner.copy(instanceId = "work"))
        assertNotEquals(first.descriptor.name, second.descriptor.name)
        assertNotEquals(first.policyKey, second.policyKey)
        assertEquals(first.descriptor.name, bound().descriptor.name)
        assertEquals("Get forecast", first.displayName)
        assertTrue(first.descriptor.name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        assertEquals(2, listOf(first, second).indexedTools().size)
        val sameIdentity = bound(name = "another_alias", identity = first.identity)
        assertFailsWith<IllegalArgumentException> { listOf(first, sameIdentity).indexedTools() }
    }

    @Test fun modelsSeeOwnNamesUnlessTheyClash() {
        val forecast = bound()
        val work = bound(owner.copy(instanceId = "work"))
        val memory = bound(PluginInstanceId("kinuvo.core"), "memory")
        val named = listOf(forecast, work, memory).withModelNames()
        assertEquals(listOf(forecast.descriptor.name, work.descriptor.name, "memory"), named.map { it.descriptor.name })
        assertEquals(memory.identity, named.last().identity)
        assertEquals(memory.descriptor.name, listOf(memory).withModelNames(taken = setOf("memory")).single().descriptor.name)
    }

    @Test fun callsRecordedUnderAnAliasStillReachTheRenamedTool() = runTest {
        val forecast = listOf(bound()).withModelNames().single()
        assertEquals("forecast", forecast.descriptor.name)
        val result = ToolService(listOf(forecast)).invoke(SessionEntry.ToolCall("c1", bound().descriptor.name, "{}"))
        assertEquals("sunny", result.output)
        assertFalse(result.isError)
    }

    @Test fun bindingPreservesRawSchemasAndCatalogOwnership() {
        val schema = buildJsonObject { put("additionalProperties", false) }
        val raw = object : Tool {
            override val descriptor = rawSchemaSpec("forecast", "Forecast", schema)
            override suspend fun execute(args: JsonObject) = "sunny"
        }
        val prepared = PluginTools(listOf(raw)).ownedBy(owner)
        assertEquals(schema, prepared.tools.single().descriptor.schema)
        assertTrue(prepared.tools.single().descriptor.verbatim)
        assertEquals(prepared.tools.single().identity, prepared.catalog.single().identity)
        assertEquals(prepared.tools.single().descriptor.name, prepared.catalog.single().id)
        val foreign = tool(identity = ToolIdentity(PluginInstanceId("another.plugin"), "forecast"))
        assertFailsWith<IllegalArgumentException> { PluginTools(listOf(foreign)).ownedBy(owner) }
    }

    @Test fun trustAndDenyUseIdentitiesAcrossAliasChangesAndStayWithinOneInstance() = runTest {
        val original = bound(identity = ToolIdentity(owner, "stable-forecast"))
        val renamed = bound(name = "renamed", identity = original.identity)
        val other = bound(owner.copy(instanceId = "work"))
        val policy = ApprovalPolicy(home.agent, home.trustRules)
        val ask = assertIs<Verdict.Ask>(policy.decide(original))
        policy.trust(original, ask.trustKeys)
        assertEquals(listOf(original.policyKey), home.trustRules.get(home.agent.id).trusted)
        assertIs<Verdict.Run>(policy.decide(renamed))
        assertIs<Verdict.Ask>(policy.decide(other))
        home.trustRules.update(home.agent.id) { it.copy(deny = DenyRules(tools = listOf(original.policyKey))) }
        assertIs<Verdict.Block>(policy.decide(renamed))
    }

    @Test fun modelAliasesAndLocalNamesCannotAuthorizeAnIdentity() = runTest {
        val target = bound()
        val policy = ApprovalPolicy(home.agent, home.trustRules)
        for (name in listOf("forecast", target.descriptor.name)) {
            home.trustRules.update(home.agent.id) { TrustRules(trusted = listOf(name)) }
            assertIs<Verdict.Ask>(policy.decide(target))
        }
        assertEquals(listOf(target.descriptor.name), home.trustRules.get(home.agent.id).trusted)
    }

    @Test fun localNameDenialsBlockEveryInstanceEvenWhenTrustedOrFull() = runTest {
        val targets = listOf(bound(), bound(owner.copy(instanceId = "work")), bound(PluginInstanceId("another.weather")))
        home.trustRules.update(home.agent.id) { TrustRules(trusted = targets.map { it.policyKey }, deny = DenyRules(tools = listOf("forecast"))) }
        for (tolerance in Tolerance.entries) {
            val policy = ApprovalPolicy(home.agent, home.trustRules, tolerance)
            targets.forEach { assertIs<Verdict.Block>(policy.decide(it)) }
        }
        home.trustRules.update(home.agent.id) { it.copy(deny = DenyRules(tools = listOf(targets.first().policyKey))) }
        val full = ApprovalPolicy(home.agent, home.trustRules, Tolerance.Full)
        assertIs<Verdict.Block>(full.decide(targets.first()))
        targets.drop(1).forEach { assertIs<Verdict.Run>(full.decide(it)) }
    }

    @Test fun nativeShellRulesAndPerAppComputerRulesKeepTheirSemantics() = runTest {
        val shell = object : Tool {
            override val descriptor = toolSpec("shell", "Shell")
            override val kind = ToolKind.Shell
            override fun assess(args: JsonObject) = Assessment(listOf(Action(Risk.Medium, listOf("git", "push"))))
            override suspend fun execute(args: JsonObject) = "ok"
        }
        val scopedShell = PluginTools(listOf(shell)).ownedBy(PluginInstanceId("kinuvo.exec")).tools.single()
        val policy = ApprovalPolicy(home.agent, home.trustRules)
        val ask = assertIs<Verdict.Ask>(policy.decide(scopedShell))
        policy.trust(scopedShell, ask.trustKeys)
        assertEquals(listOf("git push"), home.trustRules.get(home.agent.id).shell)
        assertIs<Verdict.Run>(policy.decide(scopedShell))
        val computer = object : Tool {
            override val descriptor = toolSpec("computer_act", "Computer")
            override val kind = ToolKind.ComputerAction
            override fun assess(args: JsonObject) = Assessment(listOf(Action(Risk.Medium, listOf("computer_act", "example.app"))))
            override suspend fun execute(args: JsonObject) = "ok"
        }
        val scopedComputer = PluginTools(listOf(computer)).ownedBy(PluginInstanceId("kinuvo.computer")).tools.single()
        val computerAsk = assertIs<Verdict.Ask>(policy.decide(scopedComputer))
        policy.trust(scopedComputer, computerAsk.trustKeys)
        assertIs<Verdict.Run>(policy.decide(scopedComputer))
        assertEquals(listOf("${scopedComputer.policyKey} example.app"), home.trustRules.get(home.agent.id).trusted)
    }

    @Test fun rootAndNestedCallsRecordTheirIdentitiesAndUseNormalApprovals() = runTest {
        val target = bound()
        val bridge = object : Tool {
            override val descriptor = toolSpec("bridge", "Bridge")
            override val risk = Risk.Low
            override suspend fun execute(args: JsonObject) = error("Needs a run")
            override suspend fun execute(args: JsonObject, callId: String, run: ToolRun) = run.call(target.descriptor.name, "{}").output
        }
        val scopedBridge = PluginTools(listOf(bridge)).ownedBy(PluginInstanceId("example.bridge")).tools.single()
        val model = scriptedModel { prompt, _ ->
            val history = prompt.messages
             if (history.toolResults.isEmpty())
            flowOf(StreamFrame.ToolCallComplete("call", scopedBridge.descriptor.name, "{}"), StreamFrame.End()) else flowOf(StreamFrame.End()) }
        val session = home.store.createSession(home.agent.id, "")
        val broker = ApprovalBroker()
        val events = mutableListOf<AgentEvent>()
        AgentRun(home.agent, home.store, model, listOf(scopedBridge, target),
            approvals = Approvals(ApprovalPolicy(home.agent, home.trustRules), broker)).run(session.id, "go").collect {
            events += it
            it.approval?.let { approval -> broker.respond(approval.id, ApprovalDecision.AlwaysAllow) }
        }
        assertEquals(listOf(scopedBridge.identity, target.identity), events.filterIsInstance<AgentEvent.ToolCallStart>().map { it.identity })
        assertEquals(target.identity, events.mapNotNull { it.approval }.single().identity)
        val entries = home.store.entries(home.agent.id, session.id)
        assertEquals(scopedBridge.identity, entries.filterIsInstance<SessionEntry.ToolCall>().single().identity)
        assertEquals(scopedBridge.identity, entries.filterIsInstance<SessionEntry.ToolResult>().single().identity)
        assertEquals(listOf(target.policyKey), home.trustRules.get(home.agent.id).trusted)
    }
}
