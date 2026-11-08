package kinuvo.harness.litert

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.tools.ToolNames
import java.nio.file.Files
import kotlin.test.*

class LiteRtToolsTest {
    private fun provider() = LiteRtProvider(Files.createTempDirectory("kinuvo-litert").toFile())

    @Test fun usesTheBasicPromptWithoutTheSessionInstructions() {
        val provider = provider()
        localModels.forEach {
            assertTrue(provider.basicPrompt(it.id))
            assertFalse(provider.model(AgentConfig(provider = "local", model = it.id)).instructionsInPrompt)
        }
    }

    @Test fun capsToolsPerModelAndSkipsSkillAndUiTools() {
        val provider = provider()
        val skipped = listOf(ToolNames.READ_SKILL_FILE, ToolNames.SKILL_MANAGE, ToolNames.SHOW_UI, ToolNames.SHOW_HTML, ToolNames.ACTIVATE_SKILL).map { ToolDescriptor(it, it) }
        val many = (1..20).map { ToolDescriptor("mcp_$it", "MCP $it") }
        assertEquals(mapOf("qwen3-0.6b" to 4, "gemma-4-e2b" to 8, "gemma-4-e4b" to 12),
            localModels.associate { it.id to provider.usableTools(it.id, skipped + many).size })
    }
}
