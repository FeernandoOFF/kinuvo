package kinuvo.harness.core

import kinuvo.harness.core.knowledge.KnowledgeConfig
import kinuvo.harness.core.store.config
import kinuvo.harness.core.store.readText
import kinuvo.harness.core.store.saveKnowledgeConfig
import kinuvo.harness.core.store.saveServiceConfig
import kinuvo.harness.core.store.updateConfig
import kinuvo.protocol.ServiceKind
import kinuvo.protocol.ServiceSettings
import kinuvo.protocol.TrustRules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConfigTest {
    @Test
    fun eachSectionSavesWithoutTouchingTheOthers() {
        val files = TestHome().files
        files.saveKnowledgeConfig(KnowledgeConfig(provider = "anytype", options = mapOf("space" to "s")))
        files.saveServiceConfig(ServiceKind.Search, ServiceSettings("firecrawl", mapOf("apiKey" to "\${FIRECRAWL}")))
        files.updateConfig { it.copy(timezone = "Europe/Dublin") }
        val config = files.config()
        assertEquals("anytype", config.knowledge.provider)
        assertEquals("firecrawl", config.search.provider)
        assertEquals(ServiceSettings(), config.notifications)
        assertEquals("Europe/Dublin", config.timezone)
        assertEquals(
            """
            timezone: "Europe/Dublin"
            knowledge:
              provider: "anytype"
              options:
                "space": "s"
            search:
              provider: "firecrawl"
              options:
                "apiKey": "${'$'}{FIRECRAWL}"
            """.trimIndent(),
            files.serverConfig.readText(),
        )
    }

    @Test
    fun anAgentsRulesSurviveSavingItsConfig() = runTest {
        val home = TestHome()
        home.trustRules.update(home.agent.id) { TrustRules(trusted = listOf("web_fetch")) }
        home.store.saveConfig(home.agent, home.agent.config.copy(approvals = "relaxed"))
        assertEquals(listOf("web_fetch"), home.trustRules.get(home.agent.id).trusted)
        assertEquals("relaxed", home.store.agents().single().config.approvals)
        val copy = home.store.createAgent("copy", home.agent)
        assertEquals(TrustRules(), home.trustRules.get(copy.id))
    }
}
