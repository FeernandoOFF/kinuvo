package kinuvo.app

import kinuvo.app.agent.*

import kinuvo.protocol.*
import kotlin.test.*

class ModelSelectionTest {
    @Test
    fun selectionUsesProviderAndModelSpecificReasoning() {
        val defaults = AgentSettings("same-id", "high", "default", "none", provider = "first")
        val model = ModelInfo("same-id", reasoning = listOf("low"), defaultReasoning = "low")
        val settings = SessionSettings(approvals = "strict").select("second", model, defaults)
        assertEquals("second", settings.provider)
        assertEquals("low", settings.reasoning)
        assertEquals("strict", settings.approvals)
        assertEquals("none", ModelInfo("plain", reasoning = emptyList()).reasoningFor("high"))
        assertEquals("high", ModelInfo("custom").reasoningFor("high"))
    }
    @Test fun validAgentAndSessionEffortWinsOverCatalogDefault() {
        val model = ModelInfo("model", reasoning = listOf("low", "medium", "high"), defaultReasoning = "medium")
        val defaults = AgentSettings("model", "high", "default", "none")
        assertEquals("high", model.reasoningFor(defaults.reasoning))
        assertEquals("high", SessionSettings().select("codex", model, defaults).reasoning)
        assertEquals("low", SessionSettings(reasoning = "low").select("codex", model, defaults).reasoning)
        assertEquals("medium", model.reasoningFor("unsupported"))
        assertEquals("medium", model.copy(reasoning = null).reasoningFor(""))
        assertEquals("low", model.copy(defaultReasoning = "unsupported").reasoningFor("unsupported"))
        assertEquals("none", model.copy(reasoning = emptyList()).reasoningFor("high"))
        assertEquals("high", model.copy(reasoning = null).reasoningFor("high"))
    }

}
