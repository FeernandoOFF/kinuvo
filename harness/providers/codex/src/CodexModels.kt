package kinuvo.harness.codex

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import io.ktor.http.isSuccess
import kinuvo.protocol.ModelInfo
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
            input = (m["input_modalities"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull },
            context = m["context_window"]?.jsonPrimitive?.intOrNull,
        )
    }
}
