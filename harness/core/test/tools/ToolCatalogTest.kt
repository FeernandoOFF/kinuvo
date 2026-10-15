package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolCatalogTest {
    private fun tool(name: String, description: String) = object : Tool {
        override val descriptor = RawSchemaToolDescriptor(name, description, buildJsonObject { put("type", JsonPrimitive("object")) })
        override suspend fun execute(args: JsonObject) = name
    }
    private val catalogued = listOf(tool("linear__create_issue", "Create an issue"), tool("notion__search", "Search pages"))
    private val tools = ToolCatalog.tools(catalogued).associateBy { it.descriptor.name }
    private suspend fun call(name: String, vararg args: Pair<String, String>) =
        tools.getValue(name).execute(JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    @Test fun findsByWordsAndDescribesSchemas() = runTest {
        val found = call(ToolNames.FIND_TOOLS, "query" to "create issue")
        assertTrue("linear__create_issue" in found && "notion__search" !in found)
        assertTrue("notion__search" in call(ToolNames.FIND_TOOLS, "query" to ""))
        assertTrue("\"parameters\":{\"type\":\"object\"}" in call(ToolNames.DESCRIBE_TOOL, "name" to "notion__search"))
        assertFailsWith<IllegalArgumentException> { call(ToolNames.DESCRIBE_TOOL, "name" to "nope") }
    }

    @Test fun callsGoThroughTheRun() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val run = object : ToolRun {
            override val tools = catalogued.map { it.descriptor }
            override suspend fun call(name: String, args: String) =
                kinuvo.protocol.SessionEntry.ToolResult("c", name, "done").also { calls += name to args }
        }
        val args = JsonObject(mapOf("name" to JsonPrimitive("notion__search"), "arguments" to JsonPrimitive("{\"q\":\"x\"}")))
        assertEquals("done", tools.getValue(ToolNames.CALL_TOOL).execute(args, "id", run))
        assertEquals(listOf("notion__search" to "{\"q\":\"x\"}"), calls)
    }
}
