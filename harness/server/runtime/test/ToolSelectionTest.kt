package kinuvo.harness.core

import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.server.plugin.rawSchemaSpec
import kinuvo.server.plugin.toolSpec
import kinuvo.protocol.ToolGroup
import kinuvo.server.plugin.CoreToolRegistry
import kinuvo.server.plugin.Tool
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
import kotlin.test.assertTrue

class ToolSelectionTest {
    private fun tool(name: String, group: ToolGroup, vararg required: ToolParameter) = object : Tool {
        override val coreMetadata = CoreToolRegistry[name]
        override val group = group
        override val descriptor = toolSpec(name, name, required.toList())
        override suspend fun execute(args: JsonObject) = name
    }
    private val text = ToolParameter("q", "Query", ParameterType.String)
    private val nested = ToolParameter("filter", "Filter", ParameterType.Object(listOf(text)))
    private fun names(tools: List<Tool>) = tools.map { it.descriptor.name }

    private val everything = listOf(
        tool("mcp_b", ToolGroup.External), tool("mcp_a", ToolGroup.External), tool("shell", ToolGroup.Exec), tool("run_code", ToolGroup.Automation),
        tool("ask_user", ToolGroup.Interactive), tool("activate_skill", ToolGroup.Skills), tool("tasks", ToolGroup.Data),
        tool("web_fetch", ToolGroup.Core), tool("web_search", ToolGroup.Core), tool("memory", ToolGroup.Core), tool("routine_report", ToolGroup.Internal),
    )

    @Test fun groupsFilterTools() {
        assertEquals(listOf("routine_report", "memory", "web_search"), names(ModelProfile.of(ModelTier.Micro).tools.select(everything)))
        assertTrue("web_fetch" in names(ModelProfile.of(ModelTier.Micro).tools.select(everything, ToolScope.only("web_fetch"))))
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
            override val descriptor = toolSpec("opt", "opt", listOf(text), listOf(nested))
            override suspend fun execute(args: JsonObject) = ""
        }
        assertEquals(emptyList(), optionalNested.descriptor.simplified(SchemaLevel.Flat)?.toDescriptor()?.optionalParameters)
        assertEquals(listOf("filter"), optionalNested.descriptor.simplified(SchemaLevel.OneLevel)?.toDescriptor()?.optionalParameters?.map { it.name })
        assertNull(rawSchemaSpec("raw", "", JsonObject(emptyMap())).simplified(SchemaLevel.Flat))
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
