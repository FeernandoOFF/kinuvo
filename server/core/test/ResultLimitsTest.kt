package kinuvo.server

import kinuvo.server.plugin.rawSchemaSpec
import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.Tool
import ai.koog.prompt.message.MessagePart
import kinuvo.harness.builtin.tools.*
import kinuvo.server.tools.*
import kinuvo.testing.harness.scriptedModel
import kinuvo.protocol.ModelTier
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ResultLimitsTest {
    @Test fun executorClampsCoreAndMcpCalls() = runTest {
        var received = 0
        val core = object : CoreTool {
            override val descriptor = toolSpec("contacts_lookup", "Contacts")
            override suspend fun execute(args: JsonObject): String {
                received = args.getValue("limit").jsonPrimitive.int
                return "ok"
            }
        }
        val executor = ToolService(listOf(core), maxItems = 3)
        assertFalse(executor.invoke(SessionEntry.ToolCall("call", "contacts_lookup", "{\"limit\":20}")).isError)
        assertEquals(3, received)
        executor.invoke(SessionEntry.ToolCall("default", "contacts_lookup", "{}"))
        assertEquals(3, received)
        for (parameter in listOf("limit", "max_results")) {
            val external = object : Tool {
                override val descriptor = rawSchemaSpec("mcp_search", "Search", buildJsonObject {
                    putJsonObject("properties") { putJsonObject(parameter) { put("type", "integer") } }
                })
                override suspend fun execute(args: JsonObject): String {
                    received = args.getValue(parameter).jsonPrimitive.int
                    return "ok"
                }
            }
            val mcp = ToolService(listOf(external), maxItems = 3)
            assertFalse(mcp.invoke(SessionEntry.ToolCall(parameter, "mcp_search", "{\"$parameter\":99}")).isError)
            assertEquals(3, received)
        }
    }

    @Test fun coreAndMcpItemLimits() {
        val core = object : CoreTool {
            override val descriptor = toolSpec("web_search", "Search")
            override suspend fun execute(args: JsonObject) = ""
        }
        assertEquals(mapOf("limit" to 3), core.itemLimits(3))
        assertTrue("default 3, max 3" in core.descriptor.withItemLimits(core.itemLimits(3)).toDescriptor().optionalParameters.single().description)
        val lines = object : CoreTool {
            override val descriptor = toolSpec("read_file", "Read")
            override suspend fun execute(args: JsonObject) = ""
        }
        assertTrue(lines.itemLimits(3).isEmpty())
        for (parameter in listOf("limit", "max_results")) {
            val tool = object : Tool {
                override val descriptor = rawSchemaSpec("mcp_search", "Search", buildJsonObject {
                    putJsonObject("properties") { putJsonObject(parameter) { put("type", "integer"); put("maximum", 10) } }
                })
                override suspend fun execute(args: JsonObject) = ""
            }
            val limits = tool.itemLimits(3)
            assertEquals(3, JsonObject(emptyMap()).clampItems(limits)[parameter]?.jsonPrimitive?.int)
            assertEquals(3, buildJsonObject { put(parameter, 99) }.clampItems(limits)[parameter]?.jsonPrimitive?.int)
            assertTrue("default 3, max 3" in tool.descriptor.withItemLimits(limits).schema.toString())
            assertTrue(tool.itemLimits(null).isEmpty())
        }
        val counted = object : Tool {
            override val descriptor = rawSchemaSpec("mcp_create", "Create", buildJsonObject {
                putJsonObject("properties") { putJsonObject("count") { put("type", "integer") } }
            })
            override suspend fun execute(args: JsonObject) = ""
        }
        assertTrue(counted.itemLimits(3).isEmpty())
    }
}
