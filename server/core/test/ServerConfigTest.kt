package kinuvo.server

import kinuvo.server.knowledge.KnowledgeConfig
import kinuvo.server.store.config
import kinuvo.server.plugin.readText
import kinuvo.server.store.saveKnowledgeConfig
import kinuvo.server.store.saveServiceConfig
import kinuvo.server.store.updateConfig
import kinuvo.protocol.ServiceKind
import kinuvo.protocol.ServiceSettings
import kinuvo.protocol.TrustRules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun liveVoiceDefaultsOnAndExplicitOffSurvivesOtherSaves() {
        val files = TestHome().files
        assertTrue(files.config().liveVoiceEnabled)
        files.updateConfig { it.copy(timezone = "Europe/Dublin") }
        assertTrue(files.config().liveVoiceEnabled)
        files.updateConfig { it.copy(liveVoiceEnabled = false) }
        assertFalse(files.config().liveVoiceEnabled)
        files.updateConfig { it.copy(timezone = "UTC") }
        assertFalse(files.config().liveVoiceEnabled)
        files.updateConfig { it.copy(liveVoiceEnabled = true) }
        assertTrue(files.config().liveVoiceEnabled)
    }

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
    fun reflectionModelRoundTripsAlongsideOtherSettings() {
        val files = TestHome().files
        files.updateConfig { it.copy(reflection = kinuvo.server.store.ReflectionConfig("codex", "gpt-6-luna")) }
        files.updateConfig { it.copy(timezone = "Europe/Dublin") }
        assertEquals(kinuvo.server.store.ReflectionConfig("codex", "gpt-6-luna"), files.config().reflection)
        assertEquals("Europe/Dublin", files.config().timezone)
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
