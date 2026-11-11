package kinuvo.providers.codex

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import io.ktor.http.isSuccess
import kinuvo.protocol.model.ModelInfo
import kotlinx.serialization.json.*

internal suspend fun discoverCodexModels(http: HttpClient): List<ModelInfo> {
    val response = http.get("models") { parameter("client_version", "0.160.1") }
    check(response.status.isSuccess()) { "Codex model discovery failed (${response.status.value})" }
    val bytes = response.bodyAsChannel().readBuffer(1024 * 1024L + 1).readByteArray()
    require(bytes.size <= 1024 * 1024) { "Codex model catalog is too large" }
    return parseCodexModels(bytes.decodeToString())
}

internal fun parseCodexModels(text: String): List<ModelInfo> {
    val root = Json.parseToJsonElement(text).jsonObject
    return root.getValue("models").jsonArray.mapNotNull { item ->
        val m = item.jsonObject
        val id = m["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (m["visibility"]?.jsonPrimitive?.contentOrNull == "hide") return@mapNotNull null
        ModelInfo(
            id, m["display_name"]?.jsonPrimitive?.contentOrNull ?: id,
            reasoning = (m["supported_reasoning_levels"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("effort")?.jsonPrimitive?.contentOrNull },
            defaultReasoning = m["default_reasoning_level"]?.jsonPrimitive?.contentOrNull,
            output = (m["output_modalities"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOf("text", "image").takeIf { id in IMAGE_TOOL_MODELS },
            input = (m["input_modalities"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull },
            context = m["context_window"]?.jsonPrimitive?.intOrNull,
        )
    }
}


private val IMAGE_TOOL_MODELS = setOf(
    "chat-latest", "gpt-5.2", "gpt-5.2-pro", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-pro", "gpt-5.5", "gpt-5.5-pro",
    "gpt-5.6-cyber", "gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-6-astra", "gpt-6-luna", "gpt-6-sol", "gpt-6.1-sol", "gpt-6.1-terra",
)
