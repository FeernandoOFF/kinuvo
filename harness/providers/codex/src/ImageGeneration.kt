package kinuvo.harness.codex

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.utils.io.readUTF8Line
import kinuvo.server.plugin.GeneratedImage
import kinuvo.server.plugin.ImageGeneration
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal class OpenAIImages(private val http: HttpClient) : ImageGeneration {
    override suspend fun generate(model: String, prompt: String, references: List<GeneratedImage>, size: String?): List<GeneratedImage> {
        val response = if (references.isEmpty()) http.post("images/generations") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("model", model); put("prompt", prompt); size?.let { put("size", it) } }.toString())
        } else http.post("images/edits") {
            setBody(MultiPartFormDataContent(formData {
                append("model", model)
                append("prompt", prompt)
                size?.let { append("size", it) }
                references.forEachIndexed { index, image ->
                    val extension = when (image.mimeType) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
                    append("image[]", image.bytes, Headers.build {
                        append(HttpHeaders.ContentType, image.mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"reference-$index.$extension\"")
                    })
                }
            }))
        }
        check(response.status.isSuccess()) { "Image generation failed (${response.status.value})" }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data").jsonArray.map {
            GeneratedImage(Base64.decode(it.jsonObject.getValue("b64_json").jsonPrimitive.content))
        }.also { check(it.isNotEmpty()) { "The image model returned no images" } }
    }
}

internal class CodexImages(private val http: HttpClient) : ImageGeneration {
    override suspend fun generate(model: String, prompt: String, references: List<GeneratedImage>, size: String?): List<GeneratedImage> {
        val response = http.post("responses") {
            setBody(buildJsonObject {
                put("model", model)
                put("instructions", "Generate the requested image using the image_generation tool.")
                put("store", false)
                put("stream", true)
                putJsonArray("tools") { add(buildJsonObject {
                    put("type", "image_generation")
                    put("output_format", "png")
                    size?.let { put("size", it) }
                }) }
                putJsonObject("tool_choice") { put("type", "image_generation") }
                putJsonArray("input") { add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "input_text"); put("text", prompt) })
                        references.forEach { image -> add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:${image.mimeType};base64,${Base64.encode(image.bytes)}")
                        }) }
                    }
                }) }
            }.toString())
        }
        check(response.status.isSuccess()) { "Codex image generation failed (${response.status.value})" }
        val channel = response.bodyAsChannel()
        var completed = false
        val images = linkedMapOf<String, GeneratedImage>()
        fun collect(item: JsonObject) {
            if (item["type"]?.jsonPrimitive?.content != "image_generation_call") return
            val result = item["result"]?.jsonPrimitive?.contentOrNull ?: return
            images[item["id"]?.jsonPrimitive?.contentOrNull ?: result] = GeneratedImage(Base64.decode(result))
        }
        val data = StringBuilder()
        fun event() {
            if (data.isEmpty()) return
            val text = data.toString().trim()
            data.clear()
            if (text == "[DONE]") return
            val frame = Json.parseToJsonElement(text).jsonObject
            when (frame["type"]?.jsonPrimitive?.content) {
                "error", "response.failed", "response.incomplete" -> error("Codex image generation did not complete")
                "response.output_item.done" -> (frame["item"] as? JsonObject)?.let(::collect)
                "response.completed" -> {
                    (frame.getValue("response").jsonObject["output"] as? JsonArray).orEmpty().forEach { collect(it.jsonObject) }
                    completed = true
                }
            }
        }
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isEmpty()) event()
            else if (line.startsWith("data:")) { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trimStart()) }
        }
        event()
        check(completed) { "Codex image stream ended before completion" }
        return images.values.toList().also { check(it.isNotEmpty()) { "Codex returned no generated images" } }
    }
}
