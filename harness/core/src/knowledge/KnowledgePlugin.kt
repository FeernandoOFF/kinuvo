package kinuvo.harness.core.knowledge

import kinuvo.harness.core.Agent
import kinuvo.harness.core.HarnessPlugin
import kinuvo.harness.core.InstructionSection
import kinuvo.harness.core.PluginTools
import kinuvo.harness.core.RunContext
import kinuvo.harness.core.tools.ToolNames

/** The knowledge tools for Agents that have `knowledge` in their tools, and the user's conventions in their instructions. */
class KnowledgePlugin(private val knowledge: Knowledge) : HarnessPlugin {
    override val definition = kinuvo.protocol.PluginDefinition("kinuvo.knowledge", "Knowledge")
    override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
        if (!enabled(run.agent)) return PluginTools()
        val by = ChangeSource(run.agent.id, run.sessionId)
        return PluginTools(listOf(KnowledgeTool(knowledge, by), KnowledgeRewriteTool(knowledge, by)))
    }

    override suspend fun instructions(agent: Agent): InstructionSection? = if (!enabled(agent)) null else InstructionSection(
        "Knowledge",
        "The user keeps a knowledge base you can read and add to with the knowledge tool. Check it before answering about " +
            "people, projects or places in the user's life, and save durable facts about them as you learn them." +
            knowledge.conventions().trim().takeIf { it.isNotEmpty() }?.let { "\n\nHow the user organizes it:\n$it" }.orEmpty(),
    )

    private fun enabled(agent: Agent) = ToolNames.KNOWLEDGE in agent.config.tools
}
