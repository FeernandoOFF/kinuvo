package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.plugin.GeneratedImage
import kinuvo.harness.core.plugin.ImageGeneration
import kinuvo.protocol.Attachment
import kinuvo.protocol.MAX_ATTACHMENT_BYTES
import kotlinx.serialization.json.*

class GenerateImageTool(
    private val generator: ImageGeneration,
    private val model: String,
    private val references: suspend () -> List<Attachment>,
    private val bytes: (Attachment) -> ByteArray,
) : CoreTool {
    override val descriptor = ToolDescriptor(
        name = ToolNames.GENERATE_IMAGE,
        description = "Generate or edit images. For edits, pass reference attachment IDs from this Session. Returned images can be used as references in later calls.",
        requiredParameters = listOf(ToolParameterDescriptor("prompt", "Describe the image or edits", ToolParameterType.String)),
        optionalParameters = listOf(
            ToolParameterDescriptor("references", "Image attachment IDs from this Session", ToolParameterType.List(ToolParameterType.String)),
            ToolParameterDescriptor("size", "Requested size, if supported by the image model", ToolParameterType.String),
        ),
    )
    override suspend fun execute(args: JsonObject): String = error("Image generation needs a tool run")
    override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
        val prompt = args.requireString("prompt")
        val ids = (args["references"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        require(ids.size <= 16) { "Use at most 16 reference images" }
        val available = references().associateBy { it.id }
        val images = ids.map { id ->
            val attachment = requireNotNull(available[id]) { "No image attachment '$id' in this Session" }
            require(attachment.mimeType in setOf("image/png", "image/jpeg", "image/webp")) { "Unsupported reference image type" }
            GeneratedImage(bytes(attachment), attachment.mimeType)
        }
        val generated = generator.generate(model, prompt, images, args.string("size"))
        require(generated.isNotEmpty()) { "The image model returned no images" }
        val extensions = generated.map {
            require(it.bytes.isNotEmpty() && it.bytes.size <= MAX_ATTACHMENT_BYTES) { "Generated image exceeds the attachment size limit" }
            when (it.mimeType) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> error("Unsupported generated image type") }
        }
        return generated.mapIndexed { index, image ->
            val attachment = run.attach("generated-${index + 1}.${extensions[index]}", image.bytes)
            "Image attachment: ${attachment.id} (${attachment.name})"
        }.joinToString("\n")
    }
}
