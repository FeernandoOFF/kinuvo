package kinuvo.harness.browser

import kinuvo.harness.core.*
import kinuvo.harness.core.tools.policyKey
import kinuvo.protocol.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class BrowserPolicyTest {
    @Test fun browserToolsUseApprovalTrustAndServerDenyAndRequireSession(): Unit = runBlocking {
        val plugin = BrowserPlugin(File(System.getenv("KINUVO_HOME") ?: System.getProperty("java.io.tmpdir"), "policy-browser"))
        val agent = Agent("policy-agent", "Agent", AgentConfig(tools = listOf("browser")))
        var saved = TrustRules()
        var global = TrustRules()
        val rules = object : TrustRulesRepository {
            override suspend fun get(agentId: String) = saved
            override suspend fun update(agentId: String, transform: (TrustRules) -> TrustRules) { saved = transform(saved) }
            override suspend fun server() = global
        }
        try {
            assertTrue(plugin.prepare(RunContext(agent, null), false).tools.isEmpty())
            assertTrue(plugin.prepare(RunContext(agent.copy(config = agent.config.copy(tools = emptyList())), "s"), false).tools.isEmpty())
            val tools = plugin.prepare(RunContext(agent, "s"), false).ownedBy(plugin.instance).tools
            val read = tools.first { it.identity?.localId == "browser_capture" }
            val write = tools.first { it.identity?.localId == "browser_click" }
            val policy = ApprovalPolicy(agent, rules)
            assertEquals(Verdict.Run, policy.decide(read))
            val ask = assertIs<Verdict.Ask>(policy.decide(write))
            assertEquals(Risk.High, ask.assessment.risk)
            policy.trust(write, ask.trustKeys)
            assertEquals(Verdict.Run, policy.decide(write))
            global = TrustRules(deny = DenyRules(tools = listOf(write.policyKey)))
            assertIs<Verdict.Block>(policy.decide(write))
        } finally { plugin.close() }
    }
}
