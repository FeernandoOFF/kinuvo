package kinuvo.server.plugin

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolSpecsTest {
    @Test fun parametersBecomeAnObjectSchema() {
        val spec = toolSpec("find", "Find", listOf(ToolParameter("query", "Words", ParameterType.String)),
            listOf(ToolParameter("kind", "Kind", ParameterType.Enum(arrayOf("a", "b"))), ToolParameter("limit", "Max", ParameterType.Integer)))
        assertFalse(spec.verbatim)
        assertEquals("object", spec.schema["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("query"), spec.schema["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        val properties = spec.schema["properties"]!!.jsonObject
        assertEquals(setOf("query", "kind", "limit"), properties.keys)
        assertEquals(listOf("a", "b"), properties["kind"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Max", properties["limit"]!!.jsonObject["description"]?.jsonPrimitive?.content)
    }

    @Test fun rawSchemasAreVerbatim() {
        val schema = buildJsonObject { put("type", "object") }
        val spec = rawSchemaSpec("mcp", "Raw", schema)
        assertTrue(spec.verbatim)
        assertEquals(schema, spec.schema)
    }

    @Test fun withoutSecretProvidersOnlyVariablesExpand() = runTest {
        assertEquals("a-1-b", SecretResolver.None.expand("a-\${N}-\${M:-b}", mapOf("N" to "1"), "test"))
        val failure = assertFailsWith<IllegalStateException> { SecretResolver.None.expand("\${pass://V/I/f}", emptyMap(), "test") }
        assertTrue("pass://V/I/f" in failure.message.orEmpty())
    }
}
