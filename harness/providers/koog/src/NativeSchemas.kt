package kinuvo.harness.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class NativeSchemas(tools: List<ToolDescriptor>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeSchemas>
    val schemas = tools.filterIsInstance<RawSchemaToolDescriptor>().associate { it.name to RawSchemaAwareGenerator().generate(it) }
}

/** Native clients otherwise rebuild external MCP schemas from the empty parameter descriptors. */
internal class SchemaClient(private val client: LLMClient) : LLMClient() {
    override fun llmProvider() = client.llmProvider()
    override suspend fun models() = client.models()
    override suspend fun moderate(prompt: Prompt, model: LLModel) = client.moderate(prompt, model)
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        withContext(NativeSchemas(tools)) { client.execute(prompt, model, tools) }
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        client.executeStreaming(prompt, model, tools).flowOn(NativeSchemas(tools))
    override fun close() = client.close()
}

internal suspend fun nativeSchemas(body: String, kind: String): String {
    val schemas = currentCoroutineContext()[NativeSchemas]?.schemas?.takeIf { it.isNotEmpty() } ?: return body
    val request = Json.parseToJsonElement(body).jsonObject
    fun tool(element: JsonElement, field: String): JsonElement {
        val obj = element.jsonObject
        val schema = obj["name"]?.jsonPrimitive?.content?.let(schemas::get) ?: return element
        return JsonObject(obj + (field to JsonObject(schema.filterKeys { it != "\$schema" })))
    }
    val tools = request["tools"]?.jsonArray ?: return body
    val changed = when (kind) {
        "anthropic" -> tools.map { tool(it, "input_schema") }
        "google" -> tools.map { group ->
            val obj = group.jsonObject
            val key = if ("functionDeclarations" in obj) "functionDeclarations" else "function_declarations"
            JsonObject(obj + (key to JsonArray(obj.getValue(key).jsonArray.map { tool(it, "parameters") })))
        }
        else -> return body
    }
    return JsonObject(request + ("tools" to JsonArray(changed))).toString()
}

internal suspend fun OutgoingContent.withNativeSchemas(kind: String): OutgoingContent = when (this) {
    is TextContent -> TextContent(nativeSchemas(text, kind), contentType)
    is OutgoingContent.ContentWrapper -> copy(delegate().withNativeSchemas(kind))
    else -> this
}
