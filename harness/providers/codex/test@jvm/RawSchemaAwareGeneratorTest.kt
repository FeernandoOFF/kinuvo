package kinuvo.harness.codex

import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawSchemaAwareGeneratorTest {
    @Test
    fun nestedSchemaObjectOrderDoesNotChangeSerialization() {
        val first = Json.parseToJsonElement("""{"properties":{"b":{"type":"string"},"a":{"description":"A","type":"integer"}},"required":["a","b"]}""").jsonObject
        val second = Json.parseToJsonElement("""{"required":["a","b"],"properties":{"a":{"type":"integer","description":"A"},"b":{"type":"string"}}}""").jsonObject
        val generator = RawSchemaAwareGenerator()
        assertEquals(generator.generate(RawSchemaToolDescriptor("t", "d", first)).toString(),
            generator.generate(RawSchemaToolDescriptor("t", "d", second)).toString())
    }

    @Test
    fun rawSchemasPassThroughTheGenerator() {
        val schema = buildJsonObject {
            put("properties", buildJsonObject { put("q", buildJsonObject { put("oneOf", JsonArray(listOf(JsonPrimitive(1)))) }) })
        }
        val out = RawSchemaAwareGenerator().generate(RawSchemaToolDescriptor("t", "d", schema))
        assertEquals("object", out["type"]?.jsonPrimitive?.content)
        assertTrue("oneOf" in out.toString())
        // Regular Koog tools still use the stock generator
        assertEquals("object", RawSchemaAwareGenerator().generate(ToolDescriptor("n", "d"))["type"]?.jsonPrimitive?.content)
    }
}
