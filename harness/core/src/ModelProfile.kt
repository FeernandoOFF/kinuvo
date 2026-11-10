package kinuvo.harness.core

import kinuvo.harness.core.tools.ToolGroup
import kinuvo.harness.core.tools.ToolNames
import kinuvo.protocol.ModelTier

enum class PromptStyle {
    /** The small context from [basicContextFor]. */
    Basic,
    /** [Basic] plus a short Identity. */
    BasicWithIdentity,
    Full,
}

/** How much of a tool's parameter schema the model is sent; tools whose required parameters don't fit are left out. */
enum class SchemaLevel { Flat, OneLevel, Full }

/**
 * Which tools a model is offered: those in [groups] but not [excluded] core tools, the [preferred] first, at most [cap].
 * [catalogExternal] sends External tools through the tool catalog instead of one by one.
 */
data class ToolPolicy(
    val groups: Set<ToolGroup>,
    val cap: Int? = null,
    val preferred: List<String> = PREFERRED_TOOLS,
    val schema: SchemaLevel = SchemaLevel.Full,
    val parallelCalls: Boolean = true,
    val catalogExternal: Boolean = false,
    val callsPerTurn: Int? = null,
    val excluded: Set<String> = emptySet(),
)

/**
 * [latestTurnOnly] sends the latest user message and this turn's tool steps, not earlier turns, plus a note of the
 * [earlierQuestions] user messages before it (not the replies, which small models copy); [compactAtPercent] is the share
 * of the context window that triggers Compaction.
 */
data class HistoryPolicy(val latestTurnOnly: Boolean = false, val compactAtPercent: Int = 55, val earlierQuestions: Int = 0)

/**
 * Everything that varies with a model's [ModelTier]: its prompt, tools, history, step limit, Memory budget and Result limits. [maxSteps]
 * counts model requests in a turn; [memoryPercent] is the share of the context window the Memory documents may take.
 */
data class ModelProfile(
    val tier: ModelTier,
    val prompt: PromptStyle,
    val tools: ToolPolicy,
    val history: HistoryPolicy = HistoryPolicy(),
    val maxSteps: Int,
    val memoryPercent: Int = 30,
    val resultLimits: ResultLimits = ResultLimits.of(tier),
) {
    companion object {
        private val ALL = ToolGroup.entries.toSet()

        fun of(tier: ModelTier) = when (tier) {
            ModelTier.NoTools -> ModelProfile(tier, PromptStyle.Basic, ToolPolicy(emptySet(), cap = 0, schema = SchemaLevel.Flat), maxSteps = 1)
            ModelTier.Micro -> ModelProfile(tier, PromptStyle.Basic, ToolPolicy(setOf(ToolGroup.Core), cap = 5, callsPerTurn = 2, schema = SchemaLevel.Flat, parallelCalls = false,
                excluded = setOf(ToolNames.WEB_FETCH)), HistoryPolicy(latestTurnOnly = true, earlierQuestions = 4), maxSteps = 4)
            ModelTier.Small -> ModelProfile(tier, PromptStyle.BasicWithIdentity, ToolPolicy(setOf(ToolGroup.Core, ToolGroup.Data), cap = 8, callsPerTurn = 4, schema = SchemaLevel.OneLevel, parallelCalls = false),
                maxSteps = 10)
            ModelTier.Mid -> ModelProfile(tier, PromptStyle.Full, ToolPolicy(ALL - ToolGroup.Automation, catalogExternal = true), maxSteps = 50)
            ModelTier.Frontier -> ModelProfile(tier, PromptStyle.Full, ToolPolicy(ALL), maxSteps = 66)
        }
    }
}

val PREFERRED_TOOLS = listOf(ToolNames.MEMORY, ToolNames.WEB_SEARCH, ToolNames.WEB_FETCH)
