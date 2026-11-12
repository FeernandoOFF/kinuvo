package kinuvo.server

import kinuvo.harness.AgentConfig
import kinuvo.harness.builtin.plugin.*
import kinuvo.server.plugin.*
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.builtin.*
import kinuvo.server.store.ReflectionConfig
import kinuvo.protocol.tool.ToolNames
import kinuvo.testing.harness.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.model.*
import kinuvo.protocol.reflection.*
import kinuvo.protocol.session.*
import kinuvo.protocol.tool.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kinuvo.server.providers.ModelProvider

class ReflectionModelTest {
    @Test
    fun configuredDefaultAndUnavailableModelsUseFreshPrompts() = runTest {
        val configs = mutableListOf<AgentConfig>()
        var unavailable = false
        val provider = object : TestModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools"), ModelInfo("small"), ModelInfo("custom"))
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
        val fixture = RuntimeFixture(providers = listOf(provider, object : TestModelProvider by provider { override val id = "other" }))
        val harness = fixture.runtime()
        try {
            val agent = fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf(ToolNames.MEMORY)))
            val session = harness.store.createSession(agent.id, "parent-only")
            val entries = listOf(SessionEntry.UserMessage("My preference"), SessionEntry.AssistantMessage("Use examples"), SessionEntry.UserMessage("Yes"))
            for ((setting, expected) in listOf(
                ReflectionConfig() to "tools",
                ReflectionConfig("test") to "tools",
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
