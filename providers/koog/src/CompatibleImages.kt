package kinuvo.providers.koog

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kinuvo.server.plugin.GeneratedImage
import kinuvo.server.plugin.ImageGeneration
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal class CompatibleImages(private val http: HttpClient) : ImageGeneration {
    override suspend fun generate(model: String, prompt: String, references: List<GeneratedImage>, size: String?): List<GeneratedImage> {
        val response = http.post("chat/completions") {
            setBody(buildJsonObject {
                put("model", model)
                put("stream", false)
                putJsonArray("modalities") { add("image"); add("text") }
                putJsonArray("messages") { add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                        references.forEach { image -> add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${image.mimeType};base64,${Base64.encode(image.bytes)}") }
                        }) }
                    }
                }) }
            }.toString())
        }
        check(response.status.isSuccess()) { "Image generation failed (${response.status.value})" }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("choices").jsonArray.flatMap { choice ->
            (choice.jsonObject.getValue("message").jsonObject["images"] as? JsonArray).orEmpty().map { image ->
                val url = image.jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
                val match = requireNotNull(Regex("data:(image/(?:png|jpeg|webp));base64,(.+)", RegexOption.DOT_MATCHES_ALL).matchEntire(url)) { "Expected an image data URL" }
                GeneratedImage(Base64.decode(match.groupValues[2]), match.groupValues[1])
            }
        }.also { check(it.isNotEmpty()) { "The image model returned no images" } }
    }
}
