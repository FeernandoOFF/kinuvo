package kinuvo.harness.server

import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.store.ReflectionConfig
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.testing.*
import kinuvo.protocol.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReflectionModelTest {
    @Test
    fun configuredDefaultAndUnavailableModelsUseFreshPrompts() = runTest {
        val configs = mutableListOf<AgentConfig>()
        var unavailable = false
        val provider = object : ModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools"), ModelInfo("small"), ModelInfo("custom"))
            override fun reflectionModel(models: List<ModelInfo>) = "small"
            override suspend fun availability(model: String) = if (unavailable) CapabilityState.unavailable("offline") else CapabilityState()
            override fun model(config: AgentConfig): AgentModel {
                configs += config
                return scriptedModel { prompt, _ ->
                    assertEquals(1, prompt.messages.size)
                    val review = prompt.messages.filterIsInstance<Message.User>().single().textContent()
                    assertTrue(DEFAULT_REFLECTION_PROMPT in review)
                    assertTrue("[assistant-1] Assistant: Use examples" in review)
                    assertFalse("parent-only" in review)
                    flowOf(StreamFrame.End())
                }
            }
        }
        val fixture = RuntimeFixture(providers = listOf(provider, object : ModelProvider by provider { override val id = "other" }))
        val harness = fixture.runtime()
        try {
            val agent = fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf(ToolNames.MEMORY)))
            val session = harness.store.createSession(agent, "parent-only")
            val entries = listOf(SessionEntry.UserMessage("My preference"), SessionEntry.AssistantMessage("Use examples"), SessionEntry.UserMessage("Yes"))
            for ((setting, expected) in listOf(
                ReflectionConfig() to "small",
                ReflectionConfig("test") to "small",
                ReflectionConfig("test", "custom") to "custom",
                ReflectionConfig("other", "custom") to "custom",
                ReflectionConfig("missing", "custom") to "tools",
                ReflectionConfig("test", "missing") to "tools",
            )) {
                fixture.reflectionConfig = setting
                val result = harness.reflect(agent, session.id, entries)
                assertNull(result.error)
                assertEquals(if (setting.provider == "other") "other" else "test", configs.last().provider)
                assertEquals(expected, configs.last().model)
                assertEquals(expected, result.measurements.single().model)
            }
            unavailable = true
            fixture.reflectionConfig = ReflectionConfig("test", "custom")
            assertNull(harness.reflect(agent, session.id, entries).error)
            assertEquals("tools", configs.last().model)
            harness.configure(agent, session.id, SessionSettings(model = "custom", reasoning = "high"))
            val fallback = harness.reflect(agent, session.id, entries)
            assertNull(fallback.error)
            assertEquals("custom", fallback.measurements.single().model)
            assertEquals("high", fallback.measurements.single().reasoning)
        } finally { harness.close() }
    }
}
