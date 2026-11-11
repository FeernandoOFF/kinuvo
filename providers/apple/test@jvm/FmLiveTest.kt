package kinuvo.providers.apple

import ai.koog.prompt.streaming.StreamFrame
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.protocol.session.SessionEntry
import kinuvo.harness.AgentConfig
import kinuvo.testing.harness.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

class FmLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "KINUVO_FM_LIVE_TEST", matches = "license")
    fun firstRunShowsTheLicenseStep() = runBlocking {
        val provider = assertNotNull(fmProvider())
        try {
            val model = provider.models().single()
            assertEquals("license_required", model.state.reasonCode)
            assertContains(model.state.reason.orEmpty(), "sudo fm license")
            assertFalse(provider.availability(SYSTEM).actionable)
            println("Live fm first-run state: ${model.state.reason}")
        } finally { provider.close() }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KINUVO_FM_LIVE_TEST", matches = "ready")
    fun generatesStructuredToolCalls() = runBlocking {
        val provider = assertNotNull(fmProvider())
        try {
            val memory = ToolDescriptor("memory", "Save a fact in the user's Memory", listOf(
                ToolParameterDescriptor("content", "The fact to remember", ToolParameterType.String)))
            val frames = provider.model(AgentConfig(provider = "apple", model = SYSTEM))
                .request("When asked to remember a fact, request the memory tool before replying.",
                    listOf(SessionEntry.UserMessage("Remember that my favorite drink is tea.")), listOf(memory)).toList()
            val call = frames.filterIsInstance<StreamFrame.ToolCallComplete>().single()
            assertEquals("memory", call.name)
            assertContains(call.content.lowercase(), "tea")
            println("Live fm tool call: ${call.name} ${call.content}")
        } finally { provider.close() }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KINUVO_FM_LIVE_TEST", matches = "ready")
    fun generatesWithTheInstalledModel() = runBlocking {
        val provider = AppleProvider(FmLanguageModel(), Dispatchers.IO)
        try {
            assertTrue(provider.availability(SYSTEM).actionable)
            val frames = provider.model(AgentConfig(provider = "apple", model = SYSTEM))
                .request("Answer briefly.", listOf(SessionEntry.UserMessage("Say hello.")), emptyList()).toList()
            val reply = frames.filterIsInstance<StreamFrame.TextComplete>().single().text
            assertTrue(reply.isNotBlank())
            println("Live fm reply: $reply")
        } finally { provider.close() }
    }
}
