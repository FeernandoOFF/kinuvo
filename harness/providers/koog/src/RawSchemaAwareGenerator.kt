package kinuvo.harness.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kinuvo.harness.core.deterministic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class RawSchemaAwareGenerator : OpenAICompatibleToolDescriptorSchemaGenerator() {
    override fun generate(toolDescriptor: ToolDescriptor): JsonObject =
        (if (toolDescriptor is RawSchemaToolDescriptor) objectSchema(toolDescriptor.schema) else super.generate(toolDescriptor)).deterministic() as JsonObject

    /** OpenAI requires an object schema with properties; MCP servers sometimes omit them. */
    private fun objectSchema(schema: JsonObject) = buildJsonObject {
        schema.forEach { (k, v) -> if (k != "\$schema") put(k, v) }
        put("type", JsonPrimitive("object"))
        if ("properties" !in schema) put("properties", JsonObject(emptyMap()))
    }
}
