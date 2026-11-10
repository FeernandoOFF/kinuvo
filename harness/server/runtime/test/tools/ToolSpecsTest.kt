package kinuvo.harness.core.tools

import kinuvo.harness.execution.api.ToolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.server.plugin.rawSchemaSpec
import kinuvo.server.plugin.toolSpec
import kinuvo.harness.core.TestHome
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ToolSpecsTest {
    private val home = TestHome()

    private fun roundTrip(spec: ToolSpec) = spec.toDescriptor().toSpec()

    @Test fun typedSpecsSurviveTheKoogDescriptor() {
        val text = ToolParameter("text", "Text", ParameterType.String)
        val specs = listOf(
            toolSpec("none", "No parameters"),
            toolSpec("scalars", "Scalars", listOf(text, ToolParameter("n", "", ParameterType.Integer)),
                listOf(ToolParameter("f", "Float", ParameterType.Float), ToolParameter("b", "Flag", ParameterType.Boolean),
                    ToolParameter("nothing", "Nothing", ParameterType.Null))),
            toolSpec("nested", "Nested", listOf(
                ToolParameter("mode", "Mode", ParameterType.Enum(arrayOf("a", "b"))),
                ToolParameter("rows", "Rows", ParameterType.List(ParameterType.Object(listOf(text), listOf("text")))),
                ToolParameter("free", "Free", ParameterType.Object(emptyList(), additionalProperties = true)),
                ToolParameter("either", "Either", ParameterType.AnyOf(arrayOf(text, ToolParameter("count", "Count", ParameterType.Integer)))),
            )),
            AskUserTool().descriptor,
            ShowHtmlTool().descriptor,
            MemoryTool(home.agent, home.docs).descriptor,
        )
        for (spec in specs) assertEquals(spec, roundTrip(spec), spec.name)
    }

    @Test fun rawSchemasAreKeptVerbatim() {
        val raw = rawSchemaSpec("mcp_search", "Search", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("limit") { put("type", "integer"); put("maximum", 5) } }
        })
        assertEquals(raw, roundTrip(raw))
        assertEquals(raw.schema, assertIs<RawSchemaToolDescriptor>(raw.toDescriptor()).schema)
    }
}
