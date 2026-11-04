package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.toolLocalName

/** The tools a small on-device model takes, by their [local] names: Memory and web search first, at most [max], skipping ones it [can't fill in][fits]. */
fun smallModelTools(tools: List<ToolDescriptor>, max: Int, local: (ToolDescriptor) -> String = { toolLocalName(it.name) }, fits: (ToolDescriptor) -> Boolean = { true }): List<ToolDescriptor> = tools
    .filter { local(it) !in SKIPPED && fits(it) }
    .sortedWith(compareBy<ToolDescriptor> { PREFERRED.indexOf(local(it)).let { i -> if (i < 0) PREFERRED.size else i } }.thenBy { local(it) })
    .take(max)

private val PREFERRED = listOf(ToolNames.MEMORY, ToolNames.WEB_SEARCH, ToolNames.WEB_FETCH)
/** Tools that need more context or steps than an on-device model has. */
private val SKIPPED = setOf(ToolNames.SKILLS, ToolNames.ACTIVATE_SKILL, ToolNames.SHELL, ToolNames.KNOWLEDGE, ToolNames.KNOWLEDGE_REWRITE,
    ToolNames.TASKS, ToolNames.ARTIFACT, ToolNames.SCHEDULE, ToolNames.DEVICE_TOOLS, ToolNames.ASK_USER)
