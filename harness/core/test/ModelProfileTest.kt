package kinuvo.harness.core

import kinuvo.protocol.ExecutionLocation
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.ModelTier
import kinuvo.protocol.ToolGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelProfileTest {
    @Test fun infersTiers() {
        fun tier(id: String, tools: Boolean? = null, context: Int? = null, location: ExecutionLocation = ExecutionLocation.External, declared: ModelTier? = null) =
            inferTier(ModelInfo(id, tools = tools, context = context, location = location, tier = declared))
        val table = listOf(
            tier("any", declared = ModelTier.Mid) to ModelTier.Mid,
            tier("gpt-5.6-sol", tools = false, context = 128_000) to ModelTier.NoTools,
            tier("tiny", tools = true, context = 4096) to ModelTier.Micro,
            tier("local", context = 2048, location = ExecutionLocation.ServerHost) to ModelTier.Micro,
            tier("gemma3:270m") to ModelTier.Small,
            tier("smollm2:135m") to ModelTier.Small,
            tier("qwen2.5-7b") to ModelTier.Small,
            tier("llama3.1:8b", context = 128_000) to ModelTier.Small,
            tier("gemma-4-e4b") to ModelTier.Small,
            tier("gemma-4-e2b") to ModelTier.Small,
            tier("anything", context = 8192, location = ExecutionLocation.ServerHost) to ModelTier.Small,
            tier("llama-3.1-70b-instruct") to ModelTier.Mid,
            tier("mixtral-8x7b") to ModelTier.Mid,
            tier("gpt-5-mini") to ModelTier.Mid,
            tier("claude-haiku-4-5") to ModelTier.Mid,
            tier("gemini-2.5-flash") to ModelTier.Mid,
            tier("gemini-2.5-pro") to ModelTier.Frontier,
            tier("deepseek-r1:671b") to ModelTier.Frontier,
            tier("gpt-5.6-sol", tools = true, context = 400_000) to ModelTier.Frontier,
            tier("custom-model") to ModelTier.Frontier,
        )
        table.forEachIndexed { i, (actual, expected) -> assertEquals(expected, actual, "row $i") }
    }

    @Test fun declaredTierBeatsEverythingElse() {
        assertEquals(ModelTier.Frontier, inferTier(ModelInfo("qwen2.5-7b", tools = false, tier = ModelTier.Frontier)))
    }

    @Test fun parsesParameterCounts() {
        assertEquals(7.0, parameterBillions("qwen2.5-7b"))
        assertEquals(0.6, parameterBillions("qwen3-0.6b"))
        assertEquals(56.0, parameterBillions("mixtral-8x7b"))
        assertEquals(4.0, parameterBillions("gemma-4-e4b"))
        assertEquals(14.0, parameterBillions("qwen2.5-14b-instruct-1m"))
        assertEquals(0.27, parameterBillions("gemma3:270m"))
        assertNull(parameterBillions("gpt-4o"))
    }

    @Test fun defaultsPerTier() {
        val micro = ModelProfile.of(ModelTier.Micro)
        assertEquals(PromptStyle.Basic, micro.prompt)
        assertEquals(setOf(ToolGroup.Core), micro.tools.groups)
        assertEquals(5, micro.tools.cap)
        assertEquals(SchemaLevel.Flat, micro.tools.schema)
        assertEquals(true, micro.history.latestTurnOnly)
        assertEquals(4, micro.maxSteps)
        val small = ModelProfile.of(ModelTier.Small)
        assertEquals(PromptStyle.BasicWithIdentity, small.prompt)
        assertEquals(setOf(ToolGroup.Core, ToolGroup.Data), small.tools.groups)
        assertEquals(8 to 10, small.tools.cap to small.maxSteps)
        assertEquals(SchemaLevel.OneLevel, small.tools.schema)
        val mid = ModelProfile.of(ModelTier.Mid)
        assertEquals(PromptStyle.Full, mid.prompt)
        assertEquals(ToolGroup.entries.toSet() - ToolGroup.Automation, mid.tools.groups)
        assertEquals(true, mid.tools.catalogExternal)
        assertEquals(50, mid.maxSteps)
        val frontier = ModelProfile.of(ModelTier.Frontier)
        assertEquals(ToolGroup.entries.toSet(), frontier.tools.groups)
        assertEquals(emptySet(), ModelProfile.of(ModelTier.NoTools).tools.groups)
    }
}
