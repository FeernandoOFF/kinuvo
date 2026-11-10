package kinuvo.harness.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.MessagePart
import kinuvo.harness.core.tools.*
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.ModelTier
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ResultLimitsTest {
    @Test fun tierBudgets() {
        assertEquals(ResultLimits(614, 5), ResultLimits.of(ModelTier.Micro, 4096))
        assertEquals(6, ResultLimits.of(ModelTier.Micro, 8192).maxItems)
        assertEquals(400, ResultLimits.of(ModelTier.Micro, 1000).maxTokens)
        assertEquals(ResultLimits(1200, 8), ResultLimits.of(ModelTier.Small, 8000))
        assertEquals(ResultLimits(8000, 20), ResultLimits.of(ModelTier.Mid, 200000))
        assertEquals(ResultLimits(25000, null), ResultLimits.of(ModelTier.Frontier, 1000000))
        assertEquals(2457, ResultLimits.of(ModelTier.Frontier).maxTokens)
    }

    @Test fun promptLimitsPreserveStoredResults() = runTest {
        val full = "HEAD" + "😀".repeat(4000) + "TAIL"
        val old = SessionEntry.ToolResult("old", "read_file", full, isError = true)
        val current = old.copy(id = "new")
        val history = listOf(old, SessionEntry.UserMessage("continue"), current)
        val messages = SessionMessages(scriptedModel { _, _ -> emptyFlow() })
        val prompt = messages.limitedPrompt("", history, ResultLimits(400, 3), setOf("read_file"))
        val outputs = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().map { it.output }
        assertTrue(outputs[0].length < outputs[1].length)
        outputs.forEach { output ->
            assertTrue(output.startsWith("HEAD"))
            assertTrue(output.endsWith("TAIL"))
            assertTrue("chars omitted" in output && "offset/limit" in output)
            assertFalse(output.indices.any { output[it].isHighSurrogate() && (it + 1 == output.length || !output[it + 1].isLowSurrogate()) })
        }
        assertEquals(history, messages.entries(prompt.messages))
        assertEquals(full, old.output)
        val normal = "normal result"
        assertEquals(normal, normal.limitResult(25000, false) { it.length / 4 })
        var estimates = 0
        "x".repeat(200_000).limitResult(500, false) { estimates++; it.length / 4 }
        assertTrue(estimates <= 4)
    }

    @Test fun executorClampsCoreAndMcpCalls() = runTest {
        var received = 0
        val core = object : CoreTool {
            override val descriptor = ToolDescriptor("contacts_lookup", "Contacts")
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
                override val descriptor = RawSchemaToolDescriptor("mcp_search", "Search", buildJsonObject {
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

    @Test fun providerEstimatorControlsTextBudget() = runTest {
        var estimates = 0
        val model = scriptedModel { _, _ -> emptyFlow() }.copy(estimateTokens = { prompt, _ ->
            estimates++
            prompt.messages.sumOf { message -> message.parts.filterIsInstance<MessagePart.Text>().sumOf { it.text.length } }
        })
        val result = SessionEntry.ToolResult("id", "search", "HEAD" + "x".repeat(4000) + "TAIL")
        val prompt = SessionMessages(model).limitedPrompt("", listOf(result), ResultLimits(400, 3), emptySet())
        val output = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().single().output
        assertTrue(estimates > 1)
        assertTrue(output.length <= 400)
        assertFalse("offset/limit" in output)
    }

    @Test fun coreAndMcpItemLimits() {
        val core = object : CoreTool {
            override val descriptor = ToolDescriptor("web_search", "Search")
            override suspend fun execute(args: JsonObject) = ""
        }
        assertEquals(mapOf("limit" to 3), core.itemLimits(3))
        assertTrue("default 3, max 3" in core.descriptor.withItemLimits(core.itemLimits(3)).optionalParameters.single().description)
        val lines = object : CoreTool {
            override val descriptor = ToolDescriptor("read_file", "Read")
            override suspend fun execute(args: JsonObject) = ""
        }
        assertTrue(lines.itemLimits(3).isEmpty())
        for (parameter in listOf("limit", "max_results")) {
            val tool = object : Tool {
                override val descriptor = RawSchemaToolDescriptor("mcp_search", "Search", buildJsonObject {
                    putJsonObject("properties") { putJsonObject(parameter) { put("type", "integer"); put("maximum", 10) } }
                })
                override suspend fun execute(args: JsonObject) = ""
            }
            val limits = tool.itemLimits(3)
            assertEquals(3, JsonObject(emptyMap()).clampItems(limits)[parameter]?.jsonPrimitive?.int)
            assertEquals(3, buildJsonObject { put(parameter, 99) }.clampItems(limits)[parameter]?.jsonPrimitive?.int)
            assertTrue("default 3, max 3" in (tool.descriptor.withItemLimits(limits) as RawSchemaToolDescriptor).schema.toString())
            assertTrue(tool.itemLimits(null).isEmpty())
        }
        val counted = object : Tool {
            override val descriptor = RawSchemaToolDescriptor("mcp_create", "Create", buildJsonObject {
                putJsonObject("properties") { putJsonObject("count") { put("type", "integer") } }
            })
            override suspend fun execute(args: JsonObject) = ""
        }
        assertTrue(counted.itemLimits(3).isEmpty())
    }
}
