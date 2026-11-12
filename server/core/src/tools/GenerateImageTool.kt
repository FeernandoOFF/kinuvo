package kinuvo.server.tools

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.ToolRun
import kinuvo.server.plugin.requireString
import kinuvo.server.plugin.string
import kinuvo.server.plugin.GeneratedImage
import kinuvo.server.plugin.ImageGeneration
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.session.MAX_ATTACHMENT_BYTES
import kotlinx.serialization.json.*

class GenerateImageTool(
    private val generator: ImageGeneration,
    private val model: String,
    private val references: suspend () -> List<Attachment>,
    private val bytes: (Attachment) -> ByteArray,
) : CoreTool {
    override val descriptor = toolSpec(
        name = ToolNames.GENERATE_IMAGE,
        description = "Generate or edit images. For edits, pass reference attachment IDs from this Session. Returned images can be used as references in later calls.",
        requiredParameters = listOf(ToolParameter("prompt", "Describe the image or edits", ParameterType.String)),
        optionalParameters = listOf(
            ToolParameter("references", "Image attachment IDs from this Session", ParameterType.List(ParameterType.String)),
            ToolParameter("size", "Requested size, if supported by the image model", ParameterType.String),
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
