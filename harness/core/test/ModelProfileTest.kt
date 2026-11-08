package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.tools.*
import kinuvo.protocol.ExecutionLocation
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.ModelTier
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelProfileTest {
    private fun tool(name: String, group: ToolGroup, vararg required: ToolParameterDescriptor) = object : Tool {
        override val group = group
        override val descriptor = ToolDescriptor(name, name, required.toList())
        override suspend fun execute(args: JsonObject) = name
    }
    private val text = ToolParameterDescriptor("q", "Query", ToolParameterType.String)
    private val nested = ToolParameterDescriptor("filter", "Filter", ToolParameterType.Object(listOf(text)))
    private fun names(tools: List<Tool>) = tools.map { it.descriptor.name }

    private val everything = listOf(
        tool("mcp_b", ToolGroup.External), tool("mcp_a", ToolGroup.External), tool("shell", ToolGroup.Exec), tool("run_code", ToolGroup.Automation),
        tool("ask_user", ToolGroup.Interactive), tool("activate_skill", ToolGroup.Skills), tool("tasks", ToolGroup.Data),
        tool("web_fetch", ToolGroup.Core), tool("web_search", ToolGroup.Core), tool("memory", ToolGroup.Core), tool("routine_report", ToolGroup.Internal),
    )

    @Test fun infersTiers() {
        fun tier(id: String, tools: Boolean? = null, context: Int? = null, location: ExecutionLocation = ExecutionLocation.External, declared: ModelTier? = null) =
            inferTier(ModelInfo(id, tools = tools, context = context, location = location, tier = declared))
        val table = listOf(
            tier("any", declared = ModelTier.Mid) to ModelTier.Mid,
            tier("gpt-5.6-sol", tools = false, context = 128_000) to ModelTier.NoTools,
            tier("tiny", tools = true, context = 4096) to ModelTier.Micro,
            tier("local", context = 2048, location = ExecutionLocation.ServerHost) to ModelTier.Micro,
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
        assertEquals(200, frontier.graphSteps)
        assertEquals(emptySet(), ModelProfile.of(ModelTier.NoTools).tools.groups)
    }

    @Test fun stepLimitsMapToGraphSteps() {
        assertEquals(listOf(14, 32, 152, 200), listOf(ModelTier.Micro, ModelTier.Small, ModelTier.Mid, ModelTier.Frontier).map { ModelProfile.of(it).graphSteps })
    }

    @Test fun groupsFilterTools() {
        assertEquals(listOf("routine_report", "memory", "web_search", "web_fetch"), names(ModelProfile.of(ModelTier.Micro).tools.select(everything)))
        assertEquals(listOf("routine_report", "memory", "web_search", "web_fetch", "tasks"), names(ModelProfile.of(ModelTier.Small).tools.select(everything)))
        val mid = names(ModelProfile.of(ModelTier.Mid).tools.select(everything))
        assertEquals(everything.size - 1, mid.size)
        assertEquals(false, "run_code" in mid)
        assertEquals(everything.size, ModelProfile.of(ModelTier.Frontier).tools.select(everything).size)
    }

    @Test fun capKeepsPreferredToolsFirst() {
        val many = (1..20).map { tool("mcp_$it", ToolGroup.Core) } + tool("memory", ToolGroup.Core)
        val offered = names(ModelProfile.of(ModelTier.Micro).tools.select(many))
        assertEquals(listOf("memory", "mcp_1", "mcp_10", "mcp_11", "mcp_12"), offered)
    }

    @Test fun schemaLevelsDropWhatDoesNotFit() {
        val flat = ModelProfile.of(ModelTier.Micro).tools
        val oneLevel = ModelProfile.of(ModelTier.Small).tools
        val withNested = tool("nested", ToolGroup.Core, nested)
        assertEquals(emptyList(), flat.select(listOf(withNested)))
        assertEquals(listOf("nested"), names(oneLevel.select(listOf(withNested))))
        val optionalNested = object : Tool {
            override val group = ToolGroup.Core
            override val descriptor = ToolDescriptor("opt", "opt", listOf(text), listOf(nested))
            override suspend fun execute(args: JsonObject) = ""
        }
        assertEquals(emptyList(), optionalNested.descriptor.simplified(SchemaLevel.Flat)?.optionalParameters)
        assertEquals(listOf(nested), optionalNested.descriptor.simplified(SchemaLevel.OneLevel)?.optionalParameters)
        assertNull(RawSchemaToolDescriptor("raw", "", JsonObject(emptyMap())).simplified(SchemaLevel.Flat))
    }

    @Test fun providerFilterRunsBeforeTheCap() {
        val tools = (1..8).map { tool("t$it", ToolGroup.Core) }
        val offered = ModelProfile.of(ModelTier.Micro).tools.select(tools) { it.descriptor.name != "t1" }
        assertEquals(listOf("t2", "t3", "t4", "t5", "t6"), names(offered))
    }

    @Test fun scopeNarrowsBeforeThePolicy() {
        val policy = ModelProfile.of(ModelTier.Frontier).tools
        assertEquals(everything.size, policy.select(everything, ToolScope.All).size)
        assertEquals(listOf("web_fetch", "routine_report"), names(policy.select(everything, ToolScope.only("web_fetch"))))
        assertEquals(listOf("shell", "run_code", "routine_report"), names(policy.select(everything, ToolScope.groups(ToolGroup.Exec, ToolGroup.Automation))))
        val micro = ModelProfile.of(ModelTier.Micro).tools
        assertEquals(listOf("routine_report", "shell"), names(micro.select(everything, ToolScope.only("shell"))))
        assertEquals(listOf("routine_report"), names(micro.select(everything, ToolScope.groups(ToolGroup.Exec))))
    }

    @Test fun scopeSmallerThanTheCapPassesThroughWhole() {
        val tools = (1..20).map { tool("t$it", ToolGroup.Core) }
        val micro = ModelProfile.of(ModelTier.Micro).tools
        assertEquals(listOf("t1", "t10", "t2"), names(micro.select(tools, ToolScope.only("t2", "t1", "t10"))))
        assertEquals(5, micro.select(tools, ToolScope.groups(ToolGroup.Core)).size)
    }
}
