package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.TestHome
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ToolSpecsTest {
    private val home = TestHome()

    private fun roundTrip(descriptor: ToolDescriptor) = descriptor.toSpec().toDescriptor()

    @Test fun typedDescriptorsSurviveTheNeutralSpec() {
        val text = ToolParameterDescriptor("text", "Text", ToolParameterType.String)
        val descriptors = listOf(
            ToolDescriptor("none", "No parameters"),
            ToolDescriptor("scalars", "Scalars", listOf(text, ToolParameterDescriptor("n", "", ToolParameterType.Integer)),
                listOf(ToolParameterDescriptor("f", "Float", ToolParameterType.Float), ToolParameterDescriptor("b", "Flag", ToolParameterType.Boolean),
                    ToolParameterDescriptor("nothing", "Nothing", ToolParameterType.Null))),
            ToolDescriptor("nested", "Nested", listOf(
                ToolParameterDescriptor("mode", "Mode", ToolParameterType.Enum(arrayOf("a", "b"))),
                ToolParameterDescriptor("rows", "Rows", ToolParameterType.List(ToolParameterType.Object(listOf(text), listOf("text")))),
                ToolParameterDescriptor("free", "Free", ToolParameterType.Object(emptyList(), additionalProperties = true)),
                ToolParameterDescriptor("either", "Either", ToolParameterType.AnyOf(arrayOf(text, ToolParameterDescriptor("count", "Count", ToolParameterType.Integer)))),
            )),
            AskUserTool().descriptor,
            ShowHtmlTool().descriptor,
            MemoryTool(home.agent, home.docs).descriptor,
        )
        for (descriptor in descriptors) assertEquals(descriptor, roundTrip(descriptor), descriptor.name)
    }

    @Test fun rawSchemasAreKeptVerbatim() {
        val raw = RawSchemaToolDescriptor("mcp_search", "Search", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("limit") { put("type", "integer"); put("maximum", 5) } }
        })
        val spec = raw.toSpec()
        assertEquals(raw.schema, spec.schema)
        assertEquals(raw.schema, assertIs<RawSchemaToolDescriptor>(spec.toDescriptor()).schema)
    }
}
